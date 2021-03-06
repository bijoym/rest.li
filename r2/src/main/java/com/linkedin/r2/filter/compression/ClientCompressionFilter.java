/*
   Copyright (c) 2013 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.linkedin.r2.filter.compression;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.r2.filter.Filter;
import com.linkedin.r2.filter.NextFilter;
import com.linkedin.r2.filter.message.rest.RestFilter;
import com.linkedin.r2.message.RequestContext;
import com.linkedin.r2.message.rest.RestRequest;
import com.linkedin.r2.message.rest.RestResponse;
import com.linkedin.r2.transport.http.common.HttpConstants;

/**
 * Client filter for compression
 * */
public class ClientCompressionFilter implements Filter, RestFilter
{
  private static final Logger LOG = LoggerFactory.getLogger(ClientCompressionFilter.class);

  private final EncodingType _requestCompression;
  private final EncodingType[] _acceptCompression;

  private static final String NULL_COMPRESSOR_ERROR = "requestCompression must be non-null, use EncodingType.IDENTITY for no encoding.";
  private static final String SERVER_ENCODING_ERROR = "Server returned unrecognized content encoding: ";
  private static final String REQUEST_ANY_ERROR = "ANY may not be used as request encoding type: ";

  /**
   * Instantiates a client compression filter
   * @param requestCompression Specifies which compression encoding
   * was used to compress requests
   * @param acceptCompression Specifies which compression encodings
   * are accepted by the client
   */
  public ClientCompressionFilter(EncodingType requestCompression,
                                 EncodingType[] acceptCompression)
  {
    if (requestCompression == null)
    {
      throw new IllegalArgumentException(NULL_COMPRESSOR_ERROR);
    }

    if (acceptCompression == null)
    {
      acceptCompression = new EncodingType[0];
    }

    //Sanity check
    for(EncodingType type : acceptCompression)
    {
      if (type == null)
      {
        throw new IllegalArgumentException(NULL_COMPRESSOR_ERROR);
      }
    }

    if (requestCompression.equals(EncodingType.ANY))
    {
      throw new IllegalArgumentException(REQUEST_ANY_ERROR
                                         + requestCompression.getHttpName());
    }

    _requestCompression = requestCompression;
    _acceptCompression = acceptCompression;
  }

  /**
   * Builds the accept encoding header as a string
   * @return string representation of the Accept-Encoding value for this client
   */
  private String buildAcceptEncodingHeader()
  {
    //Essentially, we want to assign nonzero quality values to all those specified;
    float delta = 1.0f/(_acceptCompression.length+1);
    float currentQuality = 1.0f;

    //Special case so we don't end with an unnecessary delimiter
    StringBuilder acceptEncodingValue = new StringBuilder();
    if (_acceptCompression.length > 0)
    {
      acceptEncodingValue.append(_acceptCompression[0].getHttpName());
      acceptEncodingValue.append(CompressionConstants.QUALITY_PREFIX);
      acceptEncodingValue.append(String.format("%.2f", currentQuality));
      currentQuality = currentQuality - delta;
    }

    for(int i=1; i < _acceptCompression.length; i++)
    {
      EncodingType t = _acceptCompression[i];
      acceptEncodingValue.append(CompressionConstants.QUALITY_DELIMITER);
      acceptEncodingValue.append(t.getHttpName());
      acceptEncodingValue.append(CompressionConstants.QUALITY_PREFIX);
      acceptEncodingValue.append(String.format("%.2f", currentQuality));
      currentQuality = currentQuality - delta;
    }

    return acceptEncodingValue.toString();
  }

  /**
   * Optionally compresses outgoing REST requests
   * */
  @Override
  public void onRestRequest(RestRequest req, RequestContext requestContext,
                            Map<String, String> wireAttrs,
                            NextFilter<RestRequest, RestResponse> nextFilter)
  {
    try
    {
      //If request can be compressed, compress
      if (_requestCompression.hasCompressor())
      {
        Compressor compressor = _requestCompression.getCompressor();
        byte[] compressed = compressor.deflate(req.getEntity().asInputStream());

        if (compressed.length < req.getEntity().length())
        {
          req = req.builder().setEntity(compressed).setHeader(HttpConstants.CONTENT_ENCODING,
                                                              compressor.getContentEncodingName()).build();
        }
      }

      //Set accepted encoding for compressed response
      if (_acceptCompression.length > 0)
      {
        req = req.builder().addHeaderValue(HttpConstants.ACCEPT_ENCODING,
                                           buildAcceptEncodingHeader()).build();
      }
    }
    catch (CompressionException e)
    {
      LOG.error(e.getMessage(), e.getCause());
    }

    //Specify the actual compression algorithm used
    nextFilter.onRequest(req, requestContext, wireAttrs);
  }

  /**
   *  Decompresses server response
   */
  @Override
  public void onRestResponse(RestResponse res, RequestContext requestContext,
                             Map<String, String> wireAttrs,
                             NextFilter<RestRequest, RestResponse> nextFilter)
  {
    try
    {
      //Check for header encoding
      String compressionHeader = res.getHeader(HttpConstants.CONTENT_ENCODING);

      //Compress if necessary
      if (compressionHeader != null && res.getEntity().length() > 0)
      {
        EncodingType encoding = EncodingType.get(compressionHeader.trim().toLowerCase());
        if (encoding == null || !encoding.hasCompressor())
        {
          throw new CompressionException(SERVER_ENCODING_ERROR + compressionHeader);
        }

        if (encoding.hasCompressor())
        {
          byte[] inflated = encoding.getCompressor().inflate(res.getEntity().asInputStream());
          res = res.builder().setEntity(inflated).build();
        }
      }
    }
    catch (CompressionException e)
    {
      //NOTE: this is going to be thrown up, but this isn't quite the right type of exception
      //Will change to proper type when rest.li supports centralized filter exception handling
      throw new RuntimeException(SERVER_ENCODING_ERROR, e);
    }

    nextFilter.onResponse(res, requestContext, wireAttrs);
  }

  @Override
  public void onRestError(Throwable ex, RequestContext requestContext,
                          Map<String, String> wireAttrs,
                          NextFilter<RestRequest, RestResponse> nextFilter)
  {
    nextFilter.onError(ex, requestContext, wireAttrs);
  }

}
