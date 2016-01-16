package com.github.ambry.rest;

import com.github.ambry.router.Router;
import com.github.ambry.router.RouterFactory;


/**
 * Factory that returns null on any function.
 * <p/>
 * Public because factories are usually constructed via {@link com.github.ambry.utils.Utils#getObj(String, Object...)}
 */
public class FaultyFactory implements BlobStorageServiceFactory, NioServerFactory, RestRequestHandlerFactory, RestResponseHandlerFactory, RouterFactory {

  // for RestResponseHandlerFactory
  public FaultyFactory(Object obj1, Object obj2) {
    // don't care.
  }

  // for NioServerFactory, RestRequestHandlerFactory and RouterFactory
  public FaultyFactory(Object obj1, Object obj2, Object obj3) {
    // don't care.
  }

  // for BlobStorageServiceFactory
  public FaultyFactory(Object... objs) {
    // don't care.
  }

  @Override
  public BlobStorageService getBlobStorageService()
      throws InstantiationException {
    return null;
  }

  @Override
  public NioServer getNioServer()
      throws InstantiationException {
    return null;
  }

  @Override
  public Router getRouter()
      throws InstantiationException {
    return null;
  }

  @Override
  public RestResponseHandler getRestResponseHandler()
      throws InstantiationException {
    return null;
  }

  @Override
  public RestRequestHandler getRestRequestHandler()
      throws InstantiationException {
    return null;
  }
}