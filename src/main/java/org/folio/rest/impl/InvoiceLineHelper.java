package org.folio.rest.impl;

import static io.vertx.core.json.JsonObject.mapFrom;
import static org.folio.invoices.utils.ErrorCodes.PROHIBITED_INVOICE_LINE_CREATION;
import static org.folio.invoices.utils.HelperUtils.*;
import static org.folio.invoices.utils.ResourcePathResolver.INVOICE_LINES;
import static org.folio.invoices.utils.ResourcePathResolver.INVOICE_LINE_NUMBER;
import static org.folio.invoices.utils.ResourcePathResolver.resourceByIdPath;
import static org.folio.invoices.utils.ResourcePathResolver.resourcesPath;

import io.vertx.core.Context;
import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import me.escoffier.vertx.completablefuture.VertxCompletableFuture;

import org.folio.invoices.events.handlers.MessageAddress;
import org.folio.invoices.rest.exceptions.HttpException;
import org.folio.invoices.utils.HelperUtils;
import org.folio.invoices.utils.InvoiceLineProtectedFields;

import org.folio.rest.jaxrs.model.Invoice;
import org.folio.rest.jaxrs.model.InvoiceLine;
import org.folio.rest.jaxrs.model.InvoiceLineCollection;
import org.folio.rest.jaxrs.model.SequenceNumber;
import org.folio.rest.tools.client.interfaces.HttpClientInterface;

public class InvoiceLineHelper extends AbstractHelper {

  private static final String INVOICE_LINE_NUMBER_ENDPOINT = resourcesPath(INVOICE_LINE_NUMBER) + "?" + INVOICE_ID + "=";
  private static final String GET_INVOICE_LINES_BY_QUERY = resourcesPath(INVOICE_LINES) + "?limit=%s&offset=%s%s&lang=%s";

  InvoiceLineHelper(Map<String, String> okapiHeaders, Context ctx, String lang) {
    super(okapiHeaders, ctx, lang);
  }

  InvoiceLineHelper(HttpClientInterface httpClient, Map<String, String> okapiHeaders, Context ctx, String lang) {
    super(httpClient, okapiHeaders, ctx, lang);
  }

  public CompletableFuture<InvoiceLineCollection> getInvoiceLines(int limit, int offset, String query) {
    CompletableFuture<InvoiceLineCollection> future = new VertxCompletableFuture<>(ctx);
    try {
      String endpoint = String.format(GET_INVOICE_LINES_BY_QUERY, limit, offset, getEndpointWithQuery(query, logger), lang);
      handleGetRequest(endpoint, httpClient, ctx, okapiHeaders, logger)
        .thenCompose(jsonInvoiceLines -> VertxCompletableFuture.supplyBlockingAsync(ctx, () -> {
            if (logger.isInfoEnabled()) {
              logger.info("Successfully retrieved invoice lines: {}", jsonInvoiceLines.encodePrettily());
            }
            return jsonInvoiceLines.mapTo(InvoiceLineCollection.class);
          })
        )
        .thenAccept(future::complete)
        .exceptionally(t -> {
          future.completeExceptionally(t);
          return null;
        });
    } catch (Exception e) {
      future.completeExceptionally(e);
    }

    return future;
  }

  public CompletableFuture<InvoiceLine> getInvoiceLine(String id) {
    CompletableFuture<InvoiceLine> future = new VertxCompletableFuture<>(ctx);

    try {
      handleGetRequest(resourceByIdPath(INVOICE_LINES, id, lang), httpClient, ctx, okapiHeaders, logger)
        .thenAccept(jsonInvoiceLine -> {
          logger.info("Successfully retrieved invoice line: " + jsonInvoiceLine.encodePrettily());
          future.complete(jsonInvoiceLine.mapTo(InvoiceLine.class));
        })
        .exceptionally(t -> {
          logger.error("Error getting invoice line by id ", id);
          future.completeExceptionally(t);
          return null;
        });
    } catch (Exception e) {
      future.completeExceptionally(e);
    }

    return future;
  }

  private void updateOutOfSyncInvoiceLine(InvoiceLine invoiceLine, Invoice invoice) {
    VertxCompletableFuture.runAsync(ctx, () -> {
      logger.info("Invoice line with id={} is out of date in storage and going to be updated", invoiceLine.getId());
      InvoiceLineHelper helper = new InvoiceLineHelper(okapiHeaders, ctx, lang);
      helper.updateInvoiceLineToStorage(invoiceLine)
        .handle((ok, fail) -> {
          if (fail == null) {
            updateInvoice(invoice);
          }
          helper.closeHttpClient();
          return null;
        });
    });
  }

  /**
   * Calculate invoice line total and compare with original value if it has changed
   *
   * @param invoiceLine invoice line to update totals for
   * @param invoice invoice record
   * @return {code true} if any total value is different to original one
   */
  private Boolean reCalculateInvoiceLineTotals(InvoiceLine invoiceLine, Invoice invoice) {

    // 1. Get original values
    Double existingTotal = invoiceLine.getTotal();
    Double subTotal = invoiceLine.getSubTotal();
    Double adjustmentsTotal = invoiceLine.getAdjustmentsTotal();

    // 2. Recalculate totals
    calculateInvoiceLineTotals(invoiceLine, invoice);

    // 3. Compare if anything has changed
    return !(Objects.equals(existingTotal, invoiceLine.getTotal())
        && Objects.equals(subTotal, invoiceLine.getSubTotal())
        && Objects.equals(adjustmentsTotal, invoiceLine.getAdjustmentsTotal()));
  }

  /**
   * Gets invoice line by id and calculate total
   *
   * @param id invoice line uuid
   * @return completable future with {@link InvoiceLine} on success or an exception if processing fails
   */
  public CompletableFuture<InvoiceLine> getInvoiceLinePersistTotal(String id) {
    CompletableFuture<InvoiceLine> future = new VertxCompletableFuture<>(ctx);

    // GET invoice-line from storage
    getInvoiceLine(id)
      .thenCompose(invoiceLineFromStorage -> getInvoice(invoiceLineFromStorage).thenApply(invoice -> {
        Boolean isTotalOutOfSync = reCalculateInvoiceLineTotals(invoiceLineFromStorage, invoice);
        if (isTotalOutOfSync) {
          updateOutOfSyncInvoiceLine(invoiceLineFromStorage, invoice);
        }
        return invoiceLineFromStorage;
      }))
      .thenAccept(future::complete)
      .exceptionally(t -> {
        logger.error("Failed to get an Invoice Line by id={}", t.getCause(), id);
        future.completeExceptionally(t);
        return null;
      });
    return future;
  }

  public CompletableFuture<Void> updateInvoiceLineToStorage(InvoiceLine invoiceLine) {
    return handlePutRequest(resourceByIdPath(INVOICE_LINES, invoiceLine.getId(), lang), JsonObject.mapFrom(invoiceLine), httpClient,
        ctx, okapiHeaders, logger);
  }

  public CompletableFuture<Void> updateInvoiceLine(InvoiceLine invoiceLine) {

    // 1. Get invoice line from storage
    return getInvoiceLine(invoiceLine.getId())
      // 2. Get invoice from storage
      .thenCompose(invoiceLineFromStorage -> getInvoice(invoiceLineFromStorage).thenCompose(invoice -> {
        // 3. Validate if invoice line update is allowed
        validateInvoiceLine(invoice, invoiceLine, invoiceLineFromStorage);
        invoiceLine.setInvoiceLineNumber(invoiceLineFromStorage.getInvoiceLineNumber());

        // 4. Recalculate totals before update which also indicates if invoice requires update
        Boolean isTotalOutOfSync = reCalculateInvoiceLineTotals(invoiceLineFromStorage, invoice);

        // 5. Update invoice line in storage
        return updateInvoiceLineToStorage(invoiceLine).thenRun(() -> {
          // 6. Trigger invoice update event only if this is required
          if (isTotalOutOfSync) {
            updateInvoice(invoice);
          }
        });
      }));
  }

  private void validateInvoiceLine(Invoice existedInvoice, InvoiceLine invoiceLine, InvoiceLine existedInvoiceLine) {
    if(isPostApproval(existedInvoice)) {
      Set<String> fields = findChangedProtectedFields(invoiceLine, existedInvoiceLine, InvoiceLineProtectedFields.getFieldNames());
      verifyThatProtectedFieldsUnchanged(fields);
    }
  }

  /**
   * Creates Invoice Line if its content is valid
   * @param invoiceLine {@link InvoiceLine} to be created
   * @return completable future which might hold {@link InvoiceLine} on success, {@code null} if validation fails or an exception if any issue happens
   */
  CompletableFuture<InvoiceLine> createInvoiceLine(InvoiceLine invoiceLine) {
    return getInvoice(invoiceLine).thenApply(this::checkIfInvoiceLineCreationAllowed)
      .thenCompose(invoice -> createInvoiceLine(invoiceLine, invoice).thenApply(line -> {
        updateInvoice(invoice);
        return line;
      }));
  }

  private Invoice checkIfInvoiceLineCreationAllowed(Invoice invoice) {
    if (isPostApproval(invoice)) {
      throw new HttpException(500, PROHIBITED_INVOICE_LINE_CREATION);
    }
    return invoice;
  }

  private CompletableFuture<Invoice> getInvoice(InvoiceLine invoiceLine) {
    return getInvoiceById(invoiceLine.getInvoiceId(), lang, httpClient, ctx, okapiHeaders, logger);
  }

  /**
   * Creates Invoice Line assuming its content is valid
   * @param invoiceLine {@link InvoiceLine} to be created
   * @param invoice associated {@link Invoice} object
   * @return completable future which might hold {@link InvoiceLine} on success or an exception if any issue happens
   */
  CompletableFuture<InvoiceLine> createInvoiceLine(InvoiceLine invoiceLine, Invoice invoice) {
    invoiceLine.setId(UUID.randomUUID()
      .toString());
    invoiceLine.setInvoiceId(invoice.getId());

    JsonObject line = mapFrom(invoiceLine);

    return generateLineNumber(invoice).thenAccept(lineNumber -> line.put(INVOICE_LINE_NUMBER, lineNumber))
      .thenAccept(t -> HelperUtils.calculateInvoiceLineTotals(invoiceLine, invoice))
      .thenCompose(v -> createInvoiceLineSummary(invoiceLine, line));
  }

  private CompletableFuture<String> generateLineNumber(Invoice invoice) {
    return handleGetRequest(getInvoiceLineNumberEndpoint(invoice.getId()), httpClient, ctx, okapiHeaders, logger)
      .thenApply(sequenceNumberJson -> {
        SequenceNumber sequenceNumber = sequenceNumberJson.mapTo(SequenceNumber.class);
        return buildInvoiceLineNumber(invoice.getFolioInvoiceNo(), sequenceNumber.getSequenceNumber());
      });
  }

  private CompletionStage<InvoiceLine> createInvoiceLineSummary(InvoiceLine invoiceLine, JsonObject line) {
    return createRecordInStorage(line, resourcesPath(INVOICE_LINES))
      // After generating line number, set id and line number of the created Invoice Line
      .thenApply(id -> invoiceLine.withId(id)
        .withInvoiceLineNumber(line.getString(INVOICE_LINE_NUMBER)));
  }

  private String buildInvoiceLineNumber(String folioInvoiceNumber, String sequence) {
    return folioInvoiceNumber + "-" + sequence;
  }

  private String getInvoiceLineNumberEndpoint(String id) {
    return INVOICE_LINE_NUMBER_ENDPOINT + id;
  }

  public CompletableFuture<Void> deleteInvoiceLine(String id) {
    return getInvoiceLine(id)
      .thenCompose(line -> handleDeleteRequest(resourceByIdPath(INVOICE_LINES, id, lang), httpClient, ctx, okapiHeaders, logger)
        .thenRun(() -> updateInvoice(line.getInvoiceId())));
  }

  private void updateInvoice(Invoice invoice) {
    VertxCompletableFuture.runAsync(ctx,
        () -> sendEvent(MessageAddress.INVOICE_TOTALS, new JsonObject().put(INVOICE, JsonObject.mapFrom(invoice))));
  }

  private void updateInvoice(String invoiceId) {
    VertxCompletableFuture.runAsync(ctx,
        () -> sendEvent(MessageAddress.INVOICE_TOTALS, new JsonObject().put(INVOICE_ID, invoiceId)));
  }
}
