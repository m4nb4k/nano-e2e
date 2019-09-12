package lt.bas.nano.service.rest.mobilepayments.payments;

import com.google.inject.Inject;
import com.mongodb.client.MongoCollection;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import lt.bas.nano.backoffice.product.mobilepayments.ManagedMobilePaymentsProduct;
import lt.bas.nano.mobilepayments.Turnover;
import lt.bas.nano.mongo.MongoProvider;
import lt.bas.nano.mongo.spring.KeywordAppendingMongoMappingFilter;
import lt.bas.nano.mongo.spring.MongoTemplateFactory;
import lt.bas.nano.storage.exchange.ExchangeContentDescriptor;
import lt.bas.nano.storage.exchange.ExchangeContentFilter;
import lt.bas.nano.storage.exchange.mongo.MongoExchangeContentRepository;
import lt.bas.nano.test.mongo.TestMongoDataAuditManager;
import lt.bas.nano.service.rest.mobilepayments.Parties;
import lt.bas.nano.service.rest.mobilepayments.TestContext;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.AcceptPaymentRequest;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.AdminPaymentRequest;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.AuthorizationResponse;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.CancelPurchasePaymentRequest;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.CancellationRequest;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.ConfirmationRequest;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.InternalOperationRequest;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.Payer;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.PaymentBatchRequest;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.PaymentConfirmation;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.PaymentDetails;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.PaymentEntriesResponse;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.PaymentRequest;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.PurchasePaymentAuthorizationCompletionRequest;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.PurchasePaymentAuthorizationRequest;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.PurchasePaymentAuthorizationReversalRequest;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.PurchasePaymentRequest;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.RejectionRequest;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.SepaOperationRequest;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.TipsOperationRequest;
import lt.bas.nano.service.rest.mobilepayments.subscription.requests.MobilePaymentsSubscription;
import lt.bas.nano.service.rest.mobilepayments.topUp.IbOperation;
import lt.bas.nano.service.rest.mobilepayments.topUp.MobilePaymentsTurnover;
import lt.bas.nano.service.rest.mobilepayments.topUp.PaymentEntry;
import lt.bas.nano.service.rest.mobilepayments.topUp.StatementEntry;
import lt.bas.nano.service.rest.mobilepayments.topUp.TopUpRequest;
import lt.bas.nano.service.rest.mobilepayments.utils.MongoConfiguration;
import lt.bas.nano.service.rest.mobilepayments.utils.PojoToJson;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.testng.Assert;
import org.testng.annotations.Guice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import static com.google.common.collect.Lists.newArrayList;
import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static java.lang.Thread.sleep;
import static java.util.Collections.singleton;
import static lt.bas.nano.test.domain.NanoBasicTypesTestHelper.$;
import static lt.bas.nano.service.rest.mobilepayments.Generator.getRandomPhoneNumber;
import static lt.bas.nano.service.rest.mobilepayments.Parameters.requestContentType;
import static lt.bas.nano.service.rest.mobilepayments.payments.MongoHelper.getAccountFromPhone;
import static lt.bas.nano.service.rest.mobilepayments.payments.MongoHelper.getPartyIdFromPhone;
import static lt.bas.nano.service.rest.mobilepayments.payments.MongoHelper.getPersonCodeFromPhone;
import static lt.bas.nano.service.rest.mobilepayments.payments.MongoHelper.getPhoneProvider;
import static lt.bas.nano.service.rest.mobilepayments.subscription.requests.GetMissingDataForUpgrade.getSettingsNo;
import static lt.bas.nano.service.rest.mobilepayments.topUp.MobilePaymentsTurnover.nullToZero;
import static lt.bas.nano.service.rest.mobilepayments.topUp.MobilePaymentsTurnover.topUpTurnoverObject;
import static lt.bas.nano.service.rest.mobilepayments.utils.MongoConfiguration.nanoBackOffice;
import static lt.bas.nano.service.rest.mobilepayments.utils.MongoConfiguration.nanoProjections;
import static lt.bas.nano.service.rest.mobilepayments.utils.MongoConfiguration.settings;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.limit;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.skip;
import static org.springframework.data.mongodb.core.query.Criteria.where;

@Guice
public class PaymentsHelper {
    @Inject
    TestContext testContext;
    @Inject
    MongoHelper mongoHelper;
    @Inject
    Parties parties;
    @Inject
    MongoConfiguration mongoConfiguration;

    public PaymentEntriesResponse getPaymentEntriesList(String view) {
        String url = "/rest/mobile/payment-entries?partyId={partyId}&payerPhoneNumber={payerPhoneNumber}&skip=0&limit=100&dateFrom=" + LocalDate.now() + "&dateTill=" + LocalDate.now().plusDays(1L) + "&view={view}";

        return given()
            .pathParam("partyId", getPartyIdFromPhone(testContext.getUserPhone()))
            .pathParam("payerPhoneNumber", testContext.getUserPhone())
            .pathParam("view", view)
            .expect().statusCode(200)
            .when()
            .get(url)
            .as(PaymentEntriesResponse.class);
    }

    public Boolean operationShownInPaymentEntriesList(String view, String paymentEntry) {
        List<lt.bas.nano.service.rest.mobilepayments.payments.requests.PaymentEntry> results = getPaymentEntriesList(view).results;
        PaymentEntry mongoPaymentEntry = nanoProjections().findOne(new Query(where("_id").regex(paymentEntry)), PaymentEntry.class, "paymentEntry");

        if (paymentEntry.startsWith("CARRIER_BILLING_TOP_UP")) {
            Assert.assertTrue(mongoPaymentEntry.order.equals(-1));
        } else {
            Assert.assertTrue(mongoPaymentEntry.order >= (0));
        }

        if (results.stream().filter(o -> o.operationId.contains(paymentEntry) && o.status.equals(mongoPaymentEntry.status)).findFirst().isPresent()) {
            return true;
        }
        return false;
    }


    public void acceptPaymentRequest(AcceptPaymentRequest req) throws InterruptedException {
        String url = "/rest/mobile/requests/" + testContext.getPaymentRequestId() + "/accept";
        String json = new PojoToJson().convert(req);

        PaymentConfirmation payment = given()
            .request().body(json)
            .expect().statusCode(202)
            .when()
            .patch(url)
            .as(PaymentConfirmation.class);

        testContext.setPaymentEntry(payment.operationId);
        testContext.setPaymentConfirmationType(payment.confirmationType);
        testContext.setOperationId(getOperationId(testContext.getPaymentEntry()));
    }

    public void rejectPaymentRequest(RejectionRequest req) {
        String url = "/rest/mobile/requests/" + testContext.getPaymentRequestId();
        String json = new PojoToJson().convert(req);

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .delete(url);
    }

    public BigDecimal getPaymentFee(String operationType, BigDecimal operationAmount, String operationCurrency) {
        String feeUrl = "/rest/mobile/operations/fee";
        Object feeAmount = given()
            .param("operationType", operationType)
            .param("amount", operationAmount)
            .param("currency", operationCurrency)
            .expect().statusCode(200)
            .when()
            .get(feeUrl)
            .then()
            .extract().path("feeAmount");

        return new BigDecimal(String.valueOf(feeAmount));
    }

    public void submitPurchasePayment(PurchasePaymentRequest req) throws InterruptedException {
        String url = "/rest/mobile/purchases";
        String json = new PojoToJson().convert(req);
        PaymentConfirmation payment = given()
            .request().body(json)
            .expect().statusCode(201)
            .when()
            .post(url)
            .as(PaymentConfirmation.class);

        testContext.setMgOperationId(req.mgOperationId);
        testContext.setPaymentEntry(payment.operationId);
        testContext.setPaymentConfirmationType(payment.confirmationType);
        testContext.setOperationId(getOperationId(testContext.getPaymentEntry()));
    }

    public void cancelPayment(String paymentId) throws InterruptedException {
        String url = "/rest/mobile/operations/" + paymentId;
        CancellationRequest can = new CancellationRequest(getPartyIdFromPhone(testContext.getUserPhone()), "Test");
        String json = new PojoToJson().convert(can);

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .delete(url);

        waitUntil(() -> nanoProjections().exists(new Query(where("_id").regex(paymentId).and("status").is("CANCELED")), PaymentEntry.class, "paymentEntry"));
        Assert.assertTrue(operationShownInPaymentEntriesList("NOT_PENDING", paymentId));
    }

    public void cancelPaymentRequest(String paymentId, String reason) {
        String url = "/rest/mobile/requests/" + paymentId;
        RejectionRequest req = new RejectionRequest(reason);
        String json = new PojoToJson().convert(req);

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .delete(url);
    }

    public void cancelPurchasePayment(String paymentId, String reason) {
        String url = "/rest/mobile/purchases/" + paymentId;
        CancelPurchasePaymentRequest req = new CancelPurchasePaymentRequest(reason);
        String json = new PojoToJson().convert(req);

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .delete(url);
    }

    public void topUp(TopUpRequest req) throws InterruptedException {
        String topUpUrl = "/rest/mobile/topups";
        String json = new PojoToJson().convert(req);

        String topUpFutureOperationStatus = getFutureBalanceLimitStatus(req.amount);
        MobilePaymentsTurnover.Turnover currentTurnover = getMobilePaymentsTurnover();

        testContext.setPaymentEntry(given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .post(topUpUrl)
            .then()
            .body(matchesJsonSchemaInClasspath("topUpSuccess.json"))
            .body("operationId", is(notNullValue()))
            .extract().path("operationId"));

        testContext.setOperationId(getOperationId(testContext.getPaymentEntry()));

        waitUntil(() -> nanoProjections().exists(new Query(where("_id").is(testContext.getOperationId()).and("status").is(topUpFutureOperationStatus)), IbOperation.class, "ibOperation"));

        if (checkIfOperationExists(testContext.getOperationId(), req.serviceType)) {
            return;
        } else {
            if (topUpFutureOperationStatus.equals("COMPLETED")) {
                checkIbOperationEntries(req);
                checkPaymentEntries(req, topUpFutureOperationStatus);
                checkStatementEntries(req);
                MobilePaymentsTurnover.Turnover newTurnover = currentTurnover.add(topUpTurnoverObject(req.amount));
                Assert.assertEquals(newTurnover.daily.compareTo(getMobilePaymentsTurnover().daily), 0);
                Assert.assertEquals(newTurnover.monthly.compareTo(getMobilePaymentsTurnover().monthly), 0);
                Assert.assertEquals(newTurnover.yearly.compareTo(getMobilePaymentsTurnover().yearly), 0);
            } else if (topUpFutureOperationStatus.equals("WAITING")) {
                checkIbOperationEntries(req);
                checkPaymentEntries(req, topUpFutureOperationStatus);
                Assert.assertNull(nanoProjections().findOne(new Query(where("endToEndDocumentId").is(req.endToEndIdentification)), StatementEntry.class, "statementEntry").amount);
                Assert.assertTrue(EqualsBuilder.reflectionEquals(currentTurnover, getMobilePaymentsTurnover()));
            }
        }
    }

    public void topUp(BigDecimal amount) throws InterruptedException {
        TopUpRequest req = new TopUpRequest.Builder("PAYMENTCARD")
            .beneficiaryPhoneNumber(testContext.getUserPhone())
            .amount(amount)
            .currency("EUR")
            .feeAmount(0)
            .feeCurrency("EUR")
            .endToEndIdentification(testContext.getUserPhone() + ":TOPUP:" + getRandomPhoneNumber() + getRandomPhoneNumber())
            .beneficiaryName("ben")
            .beneficiaryPersonalCode(testContext.getPersonCode())
            .primaryDebtorBank("pri")
            .paymentDetails("Top up details")
            .cardNumber(getAccountFromPhone(testContext.getUserPhone()))
            .isRecurring(true)
            .build();

        topUp(req);
    }

    public Set<String> paymentEntryNotificationExists(String paymentId) throws InterruptedException {
        GridFsTemplate gridFS = mongoConfiguration.createGridFsTemplate(MongoConfiguration.mongoClient(), "nano-integrations", "exchangeStorage");
        MongoCollection mongoCollection = MongoConfiguration.exchangeContentMetadataDb(new MongoProvider(), new MongoTemplateFactory(singleton(new KeywordAppendingMongoMappingFilter()), new TestMongoDataAuditManager()), settings);
        MongoOperations integrationsDatabase = MongoConfiguration.integrationsDatabase(new MongoProvider(), new MongoTemplateFactory(singleton(new KeywordAppendingMongoMappingFilter()), new TestMongoDataAuditManager()), settings);

        MongoExchangeContentRepository mongoExchangeContentRepository = new MongoExchangeContentRepository(gridFS, mongoCollection, integrationsDatabase);

        waitUntil(() -> !mongoExchangeContentRepository.findByFilter(new ExchangeContentFilter.Builder()
            .keyword(paymentId)
            .build()).isEmpty());

        List<ExchangeContentDescriptor> result = mongoExchangeContentRepository.findByFilter(new ExchangeContentFilter.Builder()
            .keyword(paymentId)
            .build());

        Set<String> keywords = result.get(0).keywords;

        return keywords;
    }

    public RequestSpecification changePhoneInHeader(String phone) throws InterruptedException {
        testContext.setUserPhone(phone);
        testContext.setIBAN(getAccountFromPhone(phone));
        testContext.setPartyId(getPartyIdFromPhone(phone));
        testContext.setSubscriptionLevel(mongoHelper.getSubscriptionLevel());
        testContext.setPersonCode(getPersonCodeFromPhone(phone));
        testContext.getUserPhone();
        testContext.setProvider(getPhoneProvider(phone));

        return RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON.withCharset(requestContentType))
            .addHeader("Authorization", "Basic " + "YWRtaW46YWRtaW4='")
            .addHeader("phone-number", phone)
            .addHeader("provider", testContext.getProvider())
            .build();
    }

    public RequestSpecification restoreHeaders() throws InterruptedException {
        testContext.setUserPhone(testContext.getOriginalPhone());

        return RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON.withCharset(requestContentType))
            .addHeader("Authorization", "Basic " + "YWRtaW46YWRtaW4='")
            .addHeader("phone-number", testContext.getOriginalPhone())
            .addHeader("provider", testContext.getProvider())
            .build();
    }

    public void confirmPayment() throws InterruptedException {
        String paymentId = testContext.getOperationId();
        String paymentConfirmationType = testContext.getPaymentConfirmationType();
        String confirmationUrl = "/rest/mobile/operations/" + paymentId + "/confirm";
        ConfirmationRequest req = new ConfirmationRequest(getPartyIdFromPhone(testContext.getUserPhone()), paymentConfirmationType, testContext.getSubscriptionPassword());
        String json = new PojoToJson().convert(req);

        given()
            .request().body(json)
            .expect().statusCode(202)
            .when()
            .patch(confirmationUrl);

        waitUntil(() -> nanoProjections().exists(new Query(where("_id").is(paymentId).and("status").in("COMPLETED", "SUSPENDED", "PROCESSING")), IbOperation.class, "ibOperation"));

        waitUntil(() -> operationShownInPaymentEntriesList("NOT_PENDING", testContext.getPaymentEntry()));

        PaymentEntry paymentEntry = nanoProjections().findOne(new Query(where("_id").is(testContext.getPaymentEntry())), PaymentEntry.class, "paymentEntry");
        IbOperation ibOperation = nanoProjections().findOne(new Query(where("_id").is(paymentId)), IbOperation.class, "ibOperation");

        if (paymentEntry.status.equals("SUSPENDED")) {
            waitUntil(() -> nanoProjections().exists(new Query(where("idByInitiator").is(testContext.getOperationId() + ":CREDIT").and("status").is("SUSPENDED")), PaymentEntry.class, "paymentEntry"));
            waitUntil(() -> nanoProjections().exists(new Query(where("idByInitiator").is(testContext.getOperationId() + ":DEBIT").and("status").is("SUSPENDED")), PaymentEntry.class, "paymentEntry"));
            waitUntil(() -> nanoProjections().exists(new Query(where("_id").is(testContext.getOperationId()).and("status").is("PROCESSING")), IbOperation.class, "ibOperation"));
            return;
        }

        if (ibOperation.operationType.equals("INTERNAL") || ibOperation.operationType.equals("ONBOARDING") && !phoneHasActiveSubscription(ibOperation.beneficiaryPhoneNumber)) {
            return;
        }
        if (ibOperation.status.equals("FAILED")) {
            Assert.assertEquals(1, 2);
        }

        if (ibOperation.operationType.equals("INTERNAL")) {
            waitUntil(() -> nanoProjections().exists(new Query(where("paymentCode").is(testContext.getPaymentCode())), StatementEntry.class, "statementEntry"));
            testContext.setTransferId(nanoProjections().findOne(new Query(where("paymentCode").is(testContext.getPaymentCode())), StatementEntry.class, "statementEntry").transferId);
        }

        if (ibOperation.operationType.equals("SEPA")) {
            waitUntil(() -> nanoProjections().exists(new Query(where("details").is(testContext.getPaymentCode())), StatementEntry.class, "statementEntry"));
            testContext.setTransferId(nanoProjections().findOne(new Query(where("details").is(testContext.getPaymentCode())), StatementEntry.class, "statementEntry").transferId);
        }

        if (ibOperation.operationType.equals("INTERNAL_PAYMENT.TRANSFER")) {
            waitUntil(() -> nanoProjections().exists(new Query(where("paymentCode").is(testContext.getPaymentCode()).and("entryType").is("CREDIT")), StatementEntry.class, "statementEntry"));
            waitUntil(() -> nanoProjections().exists(new Query(where("paymentCode").is(testContext.getPaymentCode()).and("entryType").is("DEBIT")), StatementEntry.class, "statementEntry"));
        }
    }

    public static boolean phoneHasActiveSubscription(String phone) {
        if (nanoProjections().findOne(new Query(where("principals._id").is(phone).and("status").is("ACTIVE")), MobilePaymentsSubscription.class, "mobilePaymentsSubscription") == null) {
            return false;
        }

        return true;
    }

    public static Response createPaymentBatchRequestTest(PaymentBatchRequest req) {
        String url = "/rest/mobile/requests/batch";
        String json = new PojoToJson().convert(req);

        return given()
            .request().body(json)
            .expect().statusCode(207)
            .when()
            .post(url);
    }

    public void createPaymentBatchRequest(String firstPayer, String secondPayer) {
        PaymentBatchRequest req = new PaymentBatchRequest(getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", LocalDate.now().toString(), "Test",
            newArrayList(new Payer(firstPayer, $(1), "EUR"), new Payer(secondPayer, $(1), "EUR")));

        ArrayList<HashMap> results = createPaymentBatchRequestTest(req).getBody().jsonPath().get("");
        Assert.assertEquals(results.get(0).get("payerPhoneNumber"), (firstPayer));
        Assert.assertEquals(results.get(0).get("status"), (200));
        Assert.assertEquals(results.get(1).get("payerPhoneNumber"), (secondPayer));
        Assert.assertEquals(results.get(1).get("status"), (200));
    }

    public static final int waitCount = 1000;
    public static int i = 0;

    public String getRandomSubscribedPhone(String level) {
        Long count = nanoProjections().getCollection("mobilePaymentsSubscription").count();
        String number = "";

        while (i < waitCount) {
            Aggregation aggregation = newAggregation(skip(ThreadLocalRandom.current().nextLong(count)), limit(1L));
            AggregationResults<lt.bas.nano.query.mobilepayments.MobilePaymentsSubscription> results = nanoProjections().aggregate(aggregation, "mobilePaymentsSubscription", lt.bas.nano.query.mobilepayments.MobilePaymentsSubscription.class);
            i++;
            if (results.getMappedResults().get(0).subscriptionComplianceLevel.toString().equals(level) && results.getMappedResults().get(0).status.name().equals("ACTIVE") && mongoHelper.contractIsActive(results.getMappedResults().get(0).partyId)) {
                number = results.getMappedResults().get(0).getPhoneNumber();
                break;
            }
        }
        return number;
    }

    public void createPayment(BigDecimal amount) throws InterruptedException {
        topUp(amount);
        submitPurchasePayment(new PurchasePaymentRequest("mgOperationId:" + getRandomPhoneNumber(), getAccountFromPhone(testContext.getUserPhone()), testContext.getFirstMerchantAccount(), 1, amount, "EUR",
            new PaymentDetails("Desc", null, "0", "Name", "City", "Name"), "0"));
        confirmPayment();
        Thread.sleep(1000);
    }

    public BigDecimal getAccountAvailableBalance() {
        return nanoProjections().findById(getPartyIdFromPhone(testContext.getUserPhone()), lt.bas.nano.query.currentaccount.CustomerAvailableFunds.class).getAccountAvailableFundsByCurrency(getAccountFromPhone(testContext.getUserPhone()), "EUR").value.setScale(2, RoundingMode.HALF_UP);
    }

    public String getOperationId(String paymentEntry) {
        if (testContext.getPaymentEntry().startsWith("PAYMENT_REQUEST")) {
            return paymentEntry.substring(18, 26);
        } else if (testContext.getPaymentEntry().startsWith("INTERNAL:")) {
            return paymentEntry.substring(11, 19);
        } else if (testContext.getPaymentEntry().startsWith("ONBOARDING:")) {
            return paymentEntry.substring(13, 21);
        } else if (testContext.getPaymentEntry().startsWith("TOPUP")) {
            return paymentEntry.substring(8, 16);
        } else if (testContext.getPaymentEntry().startsWith("INTERBANK")) {
            return paymentEntry.substring(12, 20);
        } else if (testContext.getPaymentEntry().startsWith("PURCHASE")) {
            return paymentEntry.substring(11, 19);
        } else if (testContext.getPaymentEntry().startsWith("INTERNAL_TIPS:")) {
            return paymentEntry.substring(16, 24);
        } else if (testContext.getPaymentEntry().startsWith("ONBOARDING_TIPS:")) {
            return paymentEntry.substring(18, 26);
        }

        return "";
    }

    public void createPaymentRequest(PaymentRequest req) throws InterruptedException {
        String url = "/rest/mobile/requests";
        String json = new PojoToJson().convert(req);

        testContext.setPaymentEntry(given()
            .request().body(json)
            .expect().statusCode(201)
            .when()
            .post(url)
            .then()
            .extract().jsonPath().getString("paymentRequestId"));

        testContext.setPaymentRequestId(getOperationId(testContext.getPaymentEntry()));
        testContext.setOperationId(getOperationId(testContext.getPaymentEntry()));
        paymentEntryNotificationExists(testContext.getOperationId()).contains("request-received");
        paymentEntryNotificationExists(testContext.getOperationId()).contains("P2P");
        waitUntil(() -> operationShownInPaymentEntriesList("PENDING", testContext.getOperationId()));
    }

    public void createSepaPaymentOperation(String beneficiaryAccount, BigDecimal amount) throws InterruptedException {
        testContext.setPaymentCode(testContext.getUserPhone() + ":paymentDetails:" + getRandomPhoneNumber());
        createSepaPaymentOperation(new SepaOperationRequest("SEPA", getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "beneficiary", amount, "EUR",
            getPaymentFee("SEPA", testContext.getOperationAmount(), "EUR"), "EUR", testContext.getPaymentCode(), beneficiaryAccount));
    }

    public void createSepaPaymentOperation(SepaOperationRequest req) throws InterruptedException {
        String url = "/rest/mobile/operations";
        String json = new PojoToJson().convert(req);

        PaymentConfirmation payment = given()
            .request().body(json)
            .expect().statusCode(201)
            .when()
            .post(url)
            .as(PaymentConfirmation.class);

        testContext.setPaymentEntry(payment.operationId);
        testContext.setPaymentConfirmationType(payment.confirmationType);
        testContext.setOperationId(getOperationId(testContext.getPaymentEntry()));

        waitUntil(() -> nanoProjections().exists(new Query(where("_id").is(testContext.getOperationId()).and("status").is("INITIATED")), IbOperation.class, "ibOperation"));
    }

    public void createInternalPaymentOperation(String beneficiaryPhoneNumber, BigDecimal amount) throws InterruptedException {
        testContext.setPaymentCode("paymentCode:" + getRandomPhoneNumber());
        createInternalPaymentOperation(new InternalOperationRequest("INTERNAL", getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "beneficiary", amount, "EUR",
            getPaymentFee("INTERNAL", testContext.getOperationAmount(), "EUR"), "EUR", beneficiaryPhoneNumber, "paymentDetails", LocalDate.now().toString(), "instructionId", testContext.getPaymentCode()));
    }

    public void createInternalPaymentOperation(InternalOperationRequest req) throws InterruptedException {
        String url = "/rest/mobile/operations";
        String json = new PojoToJson().convert(req);

        PaymentConfirmation payment = given()
            .request().body(json)
            .expect().statusCode(201)
            .when()
            .post(url)
            .as(PaymentConfirmation.class);

        testContext.setPaymentEntry(payment.operationId);
        testContext.setPaymentConfirmationType(payment.confirmationType);
        testContext.setOperationId(getOperationId(testContext.getPaymentEntry()));

        waitUntil(() -> nanoProjections().exists(new Query(where("_id").is(testContext.getOperationId()).and("status").is("INITIATED").and("amount").is(req.amount)), IbOperation.class, "ibOperation"));
        waitUntil(() -> nanoProjections().exists(new Query(where("_id").is(testContext.getPaymentEntry()).and("status").is("INITIATED").and("amount").is(req.amount)), IbOperation.class, "paymentEntry"));

        Assert.assertFalse(operationShownInPaymentEntriesList("NOT_PENDING", testContext.getPaymentEntry()));
    }

    public void createTipsOperation(TipsOperationRequest req) throws InterruptedException {
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/purchases/" + testContext.getMgOperationId() + "/tips";

        PaymentConfirmation payment = given()
            .request().body(json)
            .expect().statusCode(201)
            .when()
            .post(url)
            .as(PaymentConfirmation.class);

        testContext.setPaymentEntry(payment.operationId);
        testContext.setPaymentConfirmationType(payment.confirmationType);
        testContext.setOperationId(getOperationId(testContext.getPaymentEntry()));

        if (phoneHasActiveSubscription(req.beneficiaryPhoneNumber)) {
            waitUntil(() -> nanoProjections().exists(new Query(where("_id").is(testContext.getOperationId()).and("operationType").is("INTERNAL_TIPS")), IbOperation.class, "ibOperation"));
        } else {
            waitUntil(() -> nanoProjections().exists(new Query(where("_id").is(testContext.getOperationId()).and("operationType").is("ONBOARDING_TIPS")), IbOperation.class, "ibOperation"));
        }
    }

    public void createPurchasePaymentAuthorization(PurchasePaymentAuthorizationRequest req) throws InterruptedException {
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/purchases/authorizations";

        AuthorizationResponse res = given()
            .request().body(json)
            .expect().statusCode(201)
            .when()
            .post(url)
            .as(AuthorizationResponse.class);

        testContext.setPaymentEntry(res.operation.operationId);
        testContext.setOperationId(getOperationId(testContext.getPaymentEntry()));

        Assert.assertEquals(req.paymentAmount, res.operation.amount);
        Assert.assertEquals(req.paymentCurrency, res.operation.currency);
        Assert.assertEquals(res.operation.status, "AUTHORIZED");
        Assert.assertEquals(res.operation.operationType, "PURCHASE");

        IbOperation operation = nanoProjections().findOne(new Query(where("_id").is(testContext.getOperationId())), IbOperation.class, "ibOperation");
        Assert.assertEquals(req.paymentDetails.storeCommonName, operation.beneficiaryName);
        EqualsBuilder.reflectionEquals(req.paymentLocation, operation.paymentLocation);
        Assert.assertEquals(req.discountAmount, operation.discountAmount);
        Assert.assertEquals(req.purchaseAmount, operation.purchaseAmount);
        //Assert.assertEquals(req.storeRisk,operation.storeRisk);
        Assert.assertEquals(req.tipsAllowed, operation.tipsAllowed);
        Assert.assertEquals(req.tipsId, operation.tipsId);
        EqualsBuilder.reflectionEquals(req.loyalty, operation.loyalty);
        Assert.assertEquals(req.posID, operation.posID);
        Assert.assertEquals(req.posOperationID, operation.posOperationID);
    }

    public void createPurchasePaymentAuthorization() throws InterruptedException {
        testContext.setMgOperationId("mgOperationId:" + getRandomPhoneNumber());
        testContext.setPaymentRequestId("requestId:" + testContext.getMgOperationId());
        PurchasePaymentAuthorizationRequest req = new PurchasePaymentAuthorizationRequest.Builder(testContext.getMgOperationId())
            .userAccount(testContext.getIBAN())
            .requestId(testContext.getPaymentRequestId())
            .merchantAccount(testContext.getFirstMerchantAccount())
            .paymentAmount($(0.01))
            .paymentCurrency("EUR")
            .finalAuthorization(true)
            .ecommerce(false)
            .transactionCountryCode("LT")
            .confirm(new PurchasePaymentAuthorizationRequest.Confirm.Builder(testContext.getPartyId()).confirmationType("PASSWORD").password(testContext.getSubscriptionPassword()).build())
            .discountAmount($(1))
            .feeAmount(getPaymentFee("PURCHASE", $(0.01), "EUR"))
            .feeCurrency("EUR")
            .operationType(1)
            .paymentDetails(new PurchasePaymentAuthorizationRequest.PaymentDetails.Builder("description:selenium").storeLogo("logo:selenium").storeBizType(0).storeCommonName("storeName:selenium").merchantCommonName("merchantName:selenium").storeAddressCity("addressCity:selenium").build())
            .paymentLocation(new PurchasePaymentAuthorizationRequest.PaymentLocation(2.22, 1.11))
            .posID("posId:selenium")
            .posOperationID("posOperationId:selenium")
            .purchaseAmount($(1))
            .storeRisk(1)
            .tipsAllowed(true)
            .tipsId("tipsId:selenium")
            .loyalty(new PurchasePaymentAuthorizationRequest.Loyalty(true, "card", "reasonCode", "reasonDesc"))
            .build();

        createPurchasePaymentAuthorization(req);
    }

    public void createPurchasePaymentAuthorizationReversal(PurchasePaymentAuthorizationReversalRequest req) throws InterruptedException {
        String json = new PojoToJson().convert(req);
        String url = "rest/mobile/purchases/authorizations/" + testContext.getMgOperationId() + "/cancel";

        AuthorizationResponse res = given()
            .expect().statusCode(200)
            .request().body(json)
            .post(url)
            .as(AuthorizationResponse.class);

        testContext.setPaymentEntry(res.operation.operationId);
        testContext.setOperationId(getOperationId(testContext.getPaymentEntry()));
        Assert.assertEquals(res.operation.status, "CANCELED");
        Assert.assertEquals(res.operation.operationType, "PURCHASE");

        testContext.getUserPhone();

        waitUntil(() -> nanoProjections().exists(new Query(where("_id").is(testContext.getOperationId()).and("status").is("CANCELLED")), IbOperation.class, "ibOperation"));
    }

    public void completePurchasePaymentAuthorization(PurchasePaymentAuthorizationCompletionRequest req) throws InterruptedException {
        String json = new PojoToJson().convert(req);
        String url = "rest/mobile/purchases/authorizations/" + testContext.getMgOperationId() + "/complete";
        AuthorizationResponse res = given()
            .expect().statusCode(200)
            .request().body(json)
            .post(url)
            .as(AuthorizationResponse.class);

        testContext.setPaymentEntry(res.operation.operationId);
        testContext.setOperationId(getOperationId(testContext.getPaymentEntry()));

        Assert.assertEquals(res.operation.status, "PROCESSING");
        Assert.assertEquals(res.operation.operationType, "PURCHASE");
        waitUntil(() -> nanoProjections().exists(new Query(where("_id").is(testContext.getOperationId()).and("status").is("COMPLETED")), IbOperation.class, "ibOperation"));

        given()
            .expect().statusCode(200)
            .request().body(json)
            .post(url)
            .then()
            .body("operation.status", equalTo("COMPLETED"))
            .body("operation.operationId", equalTo(testContext.getPaymentEntry()));
    }

    public void completePurchasePaymentAuthorization() throws InterruptedException {
        PurchasePaymentAuthorizationCompletionRequest req = new PurchasePaymentAuthorizationCompletionRequest.Builder("requestId:" + testContext.getMgOperationId())
            .userAccount(testContext.getIBAN())
            .merchantAccount(testContext.getFirstMerchantAccount())
            .paymentAmount($(0.01))
            .paymentCurrency("EUR")
            .merchantFeeAmount($(0.01))
            .merchantFeeCurrency("EUR")
            .merchantFeeIncomeAccount(testContext.getInternalAccount())
            .build();

        completePurchasePaymentAuthorization(req);
    }

    public void restoreOperationLimit() throws InterruptedException {
        switch (testContext.getSubscriptionLevel()) {
            case "LOW":
                mongoHelper.setOperationLimit("LOW", $(150));
            case "AVERAGE":
                mongoHelper.setOperationLimit("AVERAGE", $(1000));
            case "TOP":
                mongoHelper.setOperationLimit("TOP", $(15000));
            default:
        }
    }

    public void restoreBalanceLimit() throws InterruptedException {
        switch (testContext.getSubscriptionLevel()) {
            case "LOW":
                mongoHelper.setBalanceLimit("LOW", $(150));
            case "AVERAGE":
                mongoHelper.setBalanceLimit("AVERAGE", $(100000));
            case "TOP":
                mongoHelper.setBalanceLimit("TOP", $(100000));
            default:
        }
    }

    public void restoreDailyDebitLimit() throws InterruptedException {
        if (testContext.getSubscriptionLevel().equals("LOW")) {
            mongoHelper.setDailyDebitTurnoverLimit("LOW", $(150));
        } else if (testContext.getSubscriptionLevel().equals("AVERAGE")) {
            mongoHelper.setDailyDebitTurnoverLimit("AVERAGE", $(1000));
        } else if (testContext.getSubscriptionLevel().equals("TOP")) {
            mongoHelper.setDailyDebitTurnoverLimit("TOP", $(15000));
        }
    }

    public void restoreMonthlyDebitLimit() throws InterruptedException {
        switch (testContext.getSubscriptionLevel()) {
            case "LOW":
                mongoHelper.setMonthlyDebitTurnoverLimit("LOW", $(150));
            case "AVERAGE":
                mongoHelper.setMonthlyDebitTurnoverLimit("AVERAGE", $(1000));
            case "TOP":
                mongoHelper.setMonthlyDebitTurnoverLimit("TOP", $(15000));
            default:
        }
    }

    public void restoreYearlyDebitLimit() throws InterruptedException {
        switch (testContext.getSubscriptionLevel()) {
            case "LOW":
                mongoHelper.setYearlyDebitTurnoverLimit("LOW", $(1800));
            case "AVERAGE":
                mongoHelper.setYearlyDebitTurnoverLimit("AVERAGE", $(1000));
            case "TOP":
                mongoHelper.setYearlyDebitTurnoverLimit("TOP", $(15000));
            default:
        }
    }

    public void submitAdminPayment(AdminPaymentRequest req) {
        String json = new PojoToJson().convert(req);
        String url = "rest/admin/payments";

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .post(url)
            .then()
            .body("paymentId", is(notNullValue()))
            .body("status", anyOf(is("COMPLETED"), is("INITIATED")));
    }

    public void checkIbOperationEntries(TopUpRequest req) throws InterruptedException {
        IbOperation obj = nanoProjections().findOne(new Query(where("_id").is(testContext.getOperationId())), IbOperation.class, "ibOperation");
        Assert.assertEquals(obj._id, testContext.getOperationId());
        Assert.assertEquals(obj.payerAccountNumber, getPayerAccountNo(req.serviceType));
        Assert.assertEquals(obj.amount, req.amount);
        Assert.assertEquals(obj.currency, req.currency);
        Assert.assertEquals(obj.paymentDetails, req.paymentDetails);
        Assert.assertEquals(obj.operationType, getOperationType(req.serviceType));
        Assert.assertEquals(obj.beneficiaryAccountNumber, getAccountFromPhone(testContext.getUserPhone()));
        Assert.assertEquals(obj.beneficiaryPhoneNumber, testContext.getUserPhone());
        Assert.assertEquals(obj.endToEndDocumentId, req.endToEndIdentification);
        Assert.assertEquals(obj.topUpServiceProvider, getServiceProvider(req.serviceType));
        Assert.assertEquals(obj.partyId, getPartyIdFromPhone(testContext.getUserPhone()));
        Assert.assertEquals(obj.status, getFutureBalanceLimitStatus(req.amount));

        if (req.serviceType.equals("PAYMENTCARD")) {
            Assert.assertTrue(obj.isRecurring.equals(req.isRecurring));
        } else if (req.serviceType.equals("NEOPAY")) {
            Assert.assertTrue(obj.isRecurring.equals(false));
        }
    }

    public void checkPaymentEntries(TopUpRequest req, String topUpFutureOperationStatus) throws InterruptedException {
        waitUntil(() -> nanoProjections().exists(new Query(where("idByInitiator").is(testContext.getOperationId() + ":CREDIT").and("status").is(topUpFutureOperationStatus)), PaymentEntry.class, "paymentEntry"));
        PaymentEntry obj = nanoProjections().findOne(new Query(where("idByInitiator").is(testContext.getOperationId() + ":CREDIT")), PaymentEntry.class, "paymentEntry");
        //Assert.assertEquals(obj._id, testContext.getPaymentEntry());
        Assert.assertEquals(obj.serviceContext.get("initiatorId"), testContext.getOperationId());
        Assert.assertEquals(obj.serviceContext.get("serviceType"), (getServiceProvider(req.serviceType) + "_" + getOperationType(req.serviceType)));
        Assert.assertEquals(obj.amount, req.amount);
        Assert.assertEquals(obj.currency, req.currency);
        Assert.assertEquals(obj.details, req.paymentDetails);
        Assert.assertEquals(obj.type, (getOperationType(req.serviceType).replaceAll("_", "")));
        Assert.assertEquals(obj.accountNumber, getAccountFromPhone(testContext.getUserPhone()));
        Assert.assertEquals(obj.phoneNumber, testContext.getUserPhone());
    }

    public void checkStatementEntries(TopUpRequest req) {
        StatementEntry obj = nanoProjections().findOne(new Query(where("endToEndDocumentId").is(req.endToEndIdentification)), StatementEntry.class, "statementEntry");
        Assert.assertEquals(obj.paymentCompleted, "true");
        Assert.assertEquals(obj.accountNumber, getAccountFromPhone(testContext.getUserPhone()));
        Assert.assertEquals(obj.amount, req.amount);
        Assert.assertEquals(obj.details, req.paymentDetails);
        Assert.assertEquals(obj.endToEndDocumentId, req.endToEndIdentification);

        if (obj.operationType.equals("TOP_UP")) {
            Assert.assertEquals(obj.type, "DEPOSIT");
            Assert.assertEquals(obj.credit, "true");
        }
    }

    public String getFutureBalanceLimitStatus(BigDecimal operationAmount) throws InterruptedException {
        if ((getProductBalanceLimit(parties.getSubscriptionInfo()).intValue() - parties.getAvailableMobileBalance(testContext.getUserPhone()).intValue()) > operationAmount.intValue() &&
            (getAllowedCreditAmount(parties.getSubscriptionInfo()).subtract(operationAmount).intValue() > 0)) {
            return "COMPLETED";
        } else if ((getProductBalanceLimit(parties.getSubscriptionInfo()).intValue() - parties.getAvailableMobileBalance(testContext.getUserPhone()).intValue()) < operationAmount.intValue() ||
            (getAllowedCreditAmount(parties.getSubscriptionInfo()).subtract(operationAmount)).intValue() < 0) {
            return "WAITING";
        }
        return "PROCESSING";

    }

    public BigDecimal getAllowedCreditAmount(String level) throws InterruptedException {
        if ((getProductTurnoverLimit(level).daily == null) || (getProductTurnoverLimit(level).monthly == null) || (getProductTurnoverLimit(level).yearly == null)) {
            return BigDecimal.valueOf(Integer.MAX_VALUE);
        }
        BigDecimal dailyLimit = getProductTurnoverLimit(level).daily.subtract(getMobilePaymentsTurnoverDebitAndCredit().daily);
        BigDecimal monthlyLimit = getProductTurnoverLimit(level).monthly.subtract(getMobilePaymentsTurnoverDebitAndCredit().monthly);
        BigDecimal yearlyLimit = getProductTurnoverLimit(level).yearly.subtract(getMobilePaymentsTurnoverDebitAndCredit().yearly);

        return min(dailyLimit, monthlyLimit, yearlyLimit);
    }

    public Turnover getProductTurnoverLimit(String level) {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        return product.complianceLevelSettings.get(getSettingsNo(level)).limits.turnoverLimit;
    }

    public BigDecimal getProductBalanceLimit(String level) {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        return product.complianceLevelSettings.get(getSettingsNo(level)).balanceLimit;
    }

    public Boolean checkIfOperationExists(String operationId, String type) {
        Instant instant = Instant.now().minus(Duration.ofMinutes(1));
        IbOperation op = nanoProjections().findOne(new Query(where("_id").is(operationId).and("topUpServiceProvider").is(getServiceProvider(type)).and("created").lt(instant)), IbOperation.class, "ibOperation");
        if (op == null) {
            return false;
        } else {
            return true;
        }
    }

    public MobilePaymentsTurnover.Turnover getMobilePaymentsTurnoverDebitAndCredit() throws InterruptedException {
        MobilePaymentsTurnover turn = nanoProjections().findOne(new Query(where("_id").is(getPartyIdFromPhone(testContext.getUserPhone()) + ":" + parties.getSubscriptionInfo().toUpperCase())), MobilePaymentsTurnover.class, "mobilePaymentsTurnover");
        if (turn == null) {
            return nullToZero(turn);
        }
        return turn.creditTurnover.add(turn.debitTurnover);
    }

    public MobilePaymentsTurnover.Turnover getMobilePaymentsTurnover() throws InterruptedException {
        MobilePaymentsTurnover turn = nanoProjections().findOne(new Query(where("_id").is(getPartyIdFromPhone(testContext.getUserPhone()) + ":" + parties.getSubscriptionInfo().toUpperCase())), MobilePaymentsTurnover.class, "mobilePaymentsTurnover");
        if (turn == null) {
            return nullToZero(turn);
        }
        return turn.creditTurnover;
    }

    public BigDecimal min(BigDecimal dailyLimit, BigDecimal monthlyLimit, BigDecimal yearlyLimit) {
        return dailyLimit.min(monthlyLimit).min(yearlyLimit);
    }

    public String getOperationType(String type) {
        switch (type) {
            case "NEOPAY":
                return "TOP_UP";
            case "PAYMENTCARD":
                return "TOP_UP";
            default:
                return null;
        }
    }

    public String getServiceProvider(String type) {
        switch (type) {
            case "NEOPAY":
                return "NEO_PAY";
            case "PAYMENTCARD":
                return "PAYMENT_CARD";
            default:
                return null;
        }
    }

    public static final int waitSleep = 100;
    private static boolean reportedWait;

    public static void waitUntil(BooleanSupplier supplier) throws InterruptedException {
        for (int i = 0; i < waitCount; i++) {
            if (supplier.getAsBoolean()) {
                return;
            } else {
                if (i > 50 && i % 5 == 0 && !reportedWait) {
                    System.out.print(".");
                    reportedWait = true;
                } else {
                    reportedWait = false;
                }
                sleep(waitSleep);
            }
        }

    }

    public String getPayerAccountNo(String type) {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        switch (type) {
            case "NEOPAY":
                return product.neoPayTopUpAccount;
            case "PAYMENTCARD":
                return product.paymentCardsAccount;
            default:
                return null;
        }
    }
}
