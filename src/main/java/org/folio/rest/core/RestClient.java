package org.folio.rest.core;

import static java.util.Objects.nonNull;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;
import static org.folio.invoices.utils.HelperUtils.getEndpointWithQuery;
import static org.folio.invoices.utils.HelperUtils.verifyAndExtractBody;
import static org.folio.rest.RestConstants.SEARCH_ENDPOINT;
import static org.folio.rest.RestVerticle.OKAPI_HEADER_TENANT;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.folio.invoices.utils.HelperUtils;
import org.folio.rest.RestConstants;
import org.folio.rest.tools.client.HttpClientFactory;
import org.folio.rest.tools.client.interfaces.HttpClientInterface;
import org.folio.rest.tools.utils.TenantTool;

import io.vertx.core.Context;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import me.escoffier.vertx.completablefuture.VertxCompletableFuture;

public class RestClient {

  private final static Logger logger = LoggerFactory.getLogger(RestClient.class);
  private static final String CALLING_ENDPOINT_MSG = "Sending {} {}";
  private static final String EXCEPTION_CALLING_ENDPOINT_MSG = "Exception calling {} {}";

  private final String baseEndpoint;
  private final String endpointById;

  public RestClient(String baseEndpoint) {
    this.baseEndpoint = baseEndpoint;
    this.endpointById = baseEndpoint + "/%s";
  }

  public <T> CompletableFuture<T> get(String query, int offset, int limit, Context context
              , Map<String, String> okapiHeaders, Class<T> responseType) {
    String endpoint = String.format(SEARCH_ENDPOINT, baseEndpoint, limit, offset, getEndpointWithQuery(query, logger));
    return get(context, okapiHeaders, endpoint, responseType);
  }

  public <T> CompletableFuture<T> getById(String id, Context context, Map<String, String> okapiHeaders, Class<T> responseType) {
    String endpoint = String.format(endpointById, id);
    return get(context, okapiHeaders, endpoint, responseType);
  }

  private <S> CompletableFuture<S> get(Context context, Map<String, String> okapiHeaders, String endpoint, Class<S> responseType) {
    CompletableFuture<S> future = new VertxCompletableFuture<>(context);
    HttpClientInterface client = getHttpClient(okapiHeaders);
    if (logger.isDebugEnabled()) {
      logger.debug("Calling GET {}", endpoint);
    }

    try {
      client
        .request(HttpMethod.GET, endpoint, okapiHeaders)
        .thenApply(response -> {
          if (logger.isDebugEnabled()) {
            logger.debug("Validating response for GET {}", endpoint);
          }
          return verifyAndExtractBody(response);
        })
        .handle((body, t) -> {
          client.closeClient();
          if (t != null) {
            logger.error(EXCEPTION_CALLING_ENDPOINT_MSG, t, HttpMethod.GET, endpoint);
            future.completeExceptionally(t.getCause());
          } else {
            if (logger.isDebugEnabled()) {
              logger.debug("The response body for GET {}: {}", endpoint, nonNull(body) ? body.encodePrettily() : null);
            }
            S responseEntity = body.mapTo(responseType);
            future.complete(responseEntity);
          }
          return null;
        });
    } catch (Exception e) {
      logger.error(EXCEPTION_CALLING_ENDPOINT_MSG, e, HttpMethod.GET, baseEndpoint);
      client.closeClient();
      future.completeExceptionally(e);
    }
    return future;
  }

  public <T> CompletableFuture<T> save(T entity, Context context, Map<String, String> okapiHeaders, Class<T> responseType) {
    CompletableFuture<T> future = new VertxCompletableFuture<>(context);
    String endpoint = baseEndpoint;
    JsonObject recordData = JsonObject.mapFrom(entity);

    if (logger.isDebugEnabled()) {
      logger.debug("Sending 'POST {}' with body: {}", endpoint, recordData.encodePrettily());
    }

    HttpClientInterface client = getHttpClient(okapiHeaders);
    try {
      client
        .request(HttpMethod.POST, recordData.toBuffer(), endpoint, okapiHeaders)
        .thenApply(HelperUtils::verifyAndExtractBody)
        .handle((body, t) -> {
          client.closeClient();
          if (t != null) {
            logger.error("'POST {}' request failed. Request body: {}", t.getCause(), endpoint, recordData.encodePrettily());
            future.completeExceptionally(t.getCause());
          } else {
            T responseEntity = body.mapTo(responseType);
            if (logger.isDebugEnabled()) {
              logger.debug("'POST {}' request successfully processed. Record with '{}' id has been created", endpoint, body);
            }
            future.complete(responseEntity);
          }
          return null;
        });
    } catch (Exception e) {
      logger.error("'POST {}' request failed. Request body: {}", e, endpoint, recordData.encodePrettily());
      client.closeClient();
      future.completeExceptionally(e);
    }

    return future;
  }

  public <T> CompletableFuture<Void> update(String id, T entity, Context context, Map<String, String> okapiHeaders) {
    CompletableFuture<Void> future = new VertxCompletableFuture<>(context);
    String endpoint = String.format(endpointById, id);
    JsonObject recordData = JsonObject.mapFrom(entity);

    if (logger.isDebugEnabled()) {
      logger.debug("Sending 'PUT {}' with body: {}", endpoint, recordData.encodePrettily());
    }

    HttpClientInterface client = getHttpClient(okapiHeaders);
    setDefaultHeaders(client);
    try {
      client
        .request(HttpMethod.PUT, recordData.toBuffer(), endpoint, okapiHeaders)
        .thenAccept(HelperUtils::verifyResponse)
        .handle((aVoid, t) -> {
          client.closeClient();
          if (t != null) {
            future.completeExceptionally(t.getCause());
            logger.error("'PUT {}' request failed. Request body: {}", t.getCause(), endpoint, recordData.encodePrettily());
          } else {
            future.complete(null);
          }
          return null;
        });
    } catch (Exception e) {
      logger.error("'PUT {}' request failed. Request body: {}", e, endpoint, recordData.encodePrettily());
      client.closeClient();
      future.completeExceptionally(e);
    }

    return future;
  }

  public CompletableFuture<Void> delete(String id, Context context, Map<String, String> okapiHeaders) {
    CompletableFuture<Void> future = new VertxCompletableFuture<>(context);
    String endpoint = String.format(endpointById, id);
    if (logger.isDebugEnabled()) {
      logger.debug(CALLING_ENDPOINT_MSG, HttpMethod.DELETE, endpoint);
    }
    HttpClientInterface client = getHttpClient(okapiHeaders);
    setDefaultHeaders(client);

    try {
      client.request(HttpMethod.DELETE, endpoint, okapiHeaders)
        .thenAccept(HelperUtils::verifyResponse)
        .handle((aVoid, t) -> {
          client.closeClient();
          if (t != null) {
            logger.error(EXCEPTION_CALLING_ENDPOINT_MSG, t, HttpMethod.DELETE, endpoint);
            future.completeExceptionally(t.getCause());
          } else {
            future.complete(null);
          }
          return null;
        });
    } catch (Exception e) {
      client.closeClient();
      logger.error(EXCEPTION_CALLING_ENDPOINT_MSG, e, HttpMethod.DELETE, endpoint);
      future.completeExceptionally(e);
    }

    return future;
  }

  private HttpClientInterface getHttpClient(Map<String, String> okapiHeaders) {
    final String okapiURL = okapiHeaders.getOrDefault(RestConstants.OKAPI_URL, "");
    final String tenantId = TenantTool.calculateTenantId(okapiHeaders.get(OKAPI_HEADER_TENANT));

    return HttpClientFactory.getHttpClient(okapiURL, tenantId);

  }

  private void setDefaultHeaders(HttpClientInterface httpClient) {
    // The RMB's HttpModuleClient2.ACCEPT is in sentence case. Using the same format to avoid duplicates
    httpClient.setDefaultHeaders(Collections.singletonMap("Accept", APPLICATION_JSON + ", " + TEXT_PLAIN));
  }
}

