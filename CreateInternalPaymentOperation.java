package lt.bas.nano.service.rest.mobilepayments.payments.tests;

import com.google.inject.Inject;
import lt.bas.nano.common.domain.Amount;
import lt.bas.nano.service.rest.mobilepayments.Parties;
import lt.bas.nano.service.rest.mobilepayments.TestContext;
import lt.bas.nano.service.rest.mobilepayments.payments.MongoHelper;
import lt.bas.nano.service.rest.mobilepayments.payments.PaymentsHelper;
import lt.bas.nano.service.rest.mobilepayments.payments.requests.InternalOperationRequest;
import lt.bas.nano.service.rest.mobilepayments.subscription.CloseSubscription;
import lt.bas.nano.service.rest.mobilepayments.topUp.IbOperation;
import lt.bas.nano.service.rest.mobilepayments.topUp.PaymentEntry;
import lt.bas.nano.service.rest.mobilepayments.utils.PojoToJson;
import org.springframework.data.mongodb.core.query.Query;
import org.testng.Assert;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static lt.bas.nano.test.domain.NanoBasicTypesTestHelper.$;
import static lt.bas.nano.service.rest.mobilepayments.Generator.getRandomPhoneNumber;
import static lt.bas.nano.service.rest.mobilepayments.payments.MongoHelper.getPartyIdFromPhone;
import static lt.bas.nano.service.rest.mobilepayments.payments.PaymentsHelper.waitUntil;
import static lt.bas.nano.service.rest.mobilepayments.utils.MongoConfiguration.nanoProjections;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.springframework.data.mongodb.core.query.Criteria.where;

@Guice
public class CreateInternalPaymentOperation {
    @Inject
    Parties parties;
    @Inject
    PaymentsHelper paymentsHelper;
    @Inject
    TestContext testContext;
    @Inject
    MongoHelper mongoHelper;
    @Inject
    CloseSubscription closeSubscription;

    String url = "/rest/mobile/operations";

    @Test
    public void createInternalPaymentOperation() throws InterruptedException {
        testContext.setOriginalPhone(testContext.getUserPhone());
        paymentsHelper.topUp($(5));
        paymentsHelper.createInternalPaymentOperation(testContext.getSC1UserPhone(), $(0.01));
        paymentsHelper.confirmPayment();
        paymentsHelper.changePhoneInHeader(testContext.getSC1UserPhone());
        waitUntil(() ->paymentsHelper.operationShownInPaymentEntriesList("NOT_PENDING",testContext.getOperationId()));
        paymentsHelper.paymentEntryNotificationExists(testContext.getOperationId()).contains("payment-received");
        paymentsHelper.paymentEntryNotificationExists(testContext.getOperationId()).contains("P2P");
        paymentsHelper.changePhoneInHeader(testContext.getOriginalPhone());
    }

    @Test
    public void createInternalPaymentOperation_WithCredit() throws InterruptedException {
        paymentsHelper.createInternalPaymentOperation(testContext.getSC1UserPhone(), $(10));
        paymentsHelper.confirmPayment();
        waitUntil(() -> paymentsHelper.operationShownInPaymentEntriesList("NOT_PENDING","CARRIER_BILLING_TOP_UP:I:" + testContext.getOperationId()+ ":CREDIT"));
        waitUntil(() -> paymentsHelper.operationShownInPaymentEntriesList("NOT_PENDING", "INTERNAL:I:" + testContext.getOperationId()+ ":DEBIT"));
        paymentsHelper.changePhoneInHeader(testContext.getSC1UserPhone());
        Assert.assertTrue(paymentsHelper.operationShownInPaymentEntriesList("NOT_PENDING","INTERNAL:I:" + testContext.getOperationId()+ ":CREDIT"));
        paymentsHelper.changePhoneInHeader(testContext.getOriginalPhone());
    }

    @Test
    public void createInternalPaymentOperation_StoppedByPrevention() throws InterruptedException {
        paymentsHelper.createInternalPaymentOperation(new InternalOperationRequest("INTERNAL", getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", testContext.getOperationAmount(), "EUR",
            paymentsHelper.getPaymentFee("INTERNAL", testContext.getOperationAmount(), "EUR"), "EUR", testContext.getSC1UserPhone(), mongoHelper.getBlacklistKeyword()));
        paymentsHelper.confirmPayment();
        waitUntil(() -> nanoProjections().exists(new Query(where("_id").is(testContext.getOperationId()).and("status").is("PROCESSING")), IbOperation.class, "ibOperation"));
        paymentsHelper.changePhoneInHeader(testContext.getSC1UserPhone());
        Assert.assertFalse(paymentsHelper.operationShownInPaymentEntriesList("PENDING", testContext.getPaymentEntry()));
        Assert.assertFalse(paymentsHelper.operationShownInPaymentEntriesList("NOT_PENDING", testContext.getPaymentEntry()));
        paymentsHelper.changePhoneInHeader(testContext.getOriginalPhone());
    }

    @Test
    public void createInternalPaymentOperation_Cancel() throws InterruptedException {
        paymentsHelper.createInternalPaymentOperation(testContext.getSC1UserPhone(), $(0.01));
        paymentsHelper.cancelPayment(testContext.getOperationId());
    }

    @Test
    public void createInternalPaymentOperation_ReceiverDailyLimitExceeded() throws InterruptedException {
        BigDecimal amount = mongoHelper.getOperationLimit("LOW").add($(1));
        paymentsHelper.topUp(amount);
        paymentsHelper.createInternalPaymentOperation(testContext.getSC1UserPhone(), amount);
        paymentsHelper.confirmPayment();
        waitUntil(() -> nanoProjections().exists(new Query(where("idByInitiator").is(testContext.getOperationId() + ":CREDIT").and("status").is("WAITING")), PaymentEntry.class, "paymentEntry"));
        waitUntil(() -> nanoProjections().exists(new Query(where("idByInitiator").is(testContext.getOperationId() + ":DEBIT").and("status").is("COMPLETED")), PaymentEntry.class, "paymentEntry"));
        paymentsHelper.changePhoneInHeader(testContext.getSC1UserPhone());
        Assert.assertFalse(paymentsHelper.operationShownInPaymentEntriesList("NOT_PENDING", testContext.getOperationId()));
        Assert.assertTrue(paymentsHelper.operationShownInPaymentEntriesList("PENDING", testContext.getOperationId()));
        paymentsHelper.paymentEntryNotificationExists(testContext.getOperationId()).contains("bank-topup-exceeds-limit");
        paymentsHelper.paymentEntryNotificationExists(testContext.getOperationId()).contains("TOPUP");
        paymentsHelper.changePhoneInHeader(testContext.getOriginalPhone());
    }

    @Test
    public void createInternalPaymentOperation_Confirm_DeactivateReceiver() throws InterruptedException {
        paymentsHelper.createInternalPaymentOperation(testContext.getSC1UserPhone(), $(0.01));
        paymentsHelper.confirmPayment();
        parties.unsubscribe(testContext.getSC1UserPhone());
        parties.activateSubscription(testContext.getSC1UserPhone());
        Assert.assertTrue(paymentsHelper.operationShownInPaymentEntriesList("NOT_PENDING", "INTERNAL:I:" + testContext.getOperationId()));

    }

    @Test
    public void createInternalPaymentOperation_DeactivateInitiator() throws InterruptedException {
        paymentsHelper.changePhoneInHeader(testContext.getFCUserPhone());
        paymentsHelper.createInternalPaymentOperation(testContext.getSC1UserPhone(), $(0.01));
        parties.unsubscribe(testContext.getUserPhone());
        waitUntil(() -> nanoProjections().exists(new Query(where("idByInitiator").is(testContext.getOperationId() + ":CREDIT").and("status").is("FAILED")), PaymentEntry.class, "paymentEntry"));
        parties.activateSubscription(testContext.getUserPhone());
        paymentsHelper.operationShownInPaymentEntriesList("NOT_PENDING", testContext.getPaymentEntry());
    }

    @Test
    public void createInternalPaymentOperation_Confirm_DeactivateInitiator() throws InterruptedException {
        paymentsHelper.createInternalPaymentOperation(testContext.getSC1UserPhone(), $(0.01));
        paymentsHelper.confirmPayment();
        parties.unsubscribe(testContext.getUserPhone());
        parties.activateSubscription(testContext.getUserPhone());
        paymentsHelper.operationShownInPaymentEntriesList("NOT_PENDING", testContext.getPaymentEntry());
    }

    //@Test
    public void createInternalPaymentOperation_Confirm_CloseInitiator() throws InterruptedException {
        paymentsHelper.createInternalPaymentOperation(testContext.getSC1UserPhone(), $(0.01));
        paymentsHelper.confirmPayment();
        closeSubscription.closeSubscription();
        Assert.assertFalse(paymentsHelper.operationShownInPaymentEntriesList("NOT_PENDING", testContext.getPaymentEntry()));
    }

    @Test
    public void createInternalPaymentOperation_InternalRollback() throws InterruptedException {
        createInternalPaymentOperation_ReceiverDailyLimitExceeded();
        paymentsHelper.changePhoneInHeader(testContext.getSC1UserPhone());
        closeSubscription.closeSubscription();
        paymentsHelper.changePhoneInHeader(testContext.getOriginalPhone());
        paymentsHelper.paymentEntryNotificationExists(testContext.getOperationId()).contains("reversal-payment-received");
        paymentsHelper.paymentEntryNotificationExists(testContext.getOperationId()).contains("P2P");
    }

    @Test
    public void createOnboardingPaymentOperation() throws InterruptedException {
        paymentsHelper.createInternalPaymentOperation(new InternalOperationRequest("INTERNAL", getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", testContext.getOperationAmount(), "EUR",
            paymentsHelper.getPaymentFee("INTERNAL", testContext.getOperationAmount(), "EUR"), "EUR", getRandomPhoneNumber(), "Test"));
        paymentsHelper.confirmPayment();
    }

    @Test
    public void createInternalPaymentOperation_Error_PartyNotFound() {
        createInternalPaymentOperation_Error_PartyNotFound("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_PartyNotFound(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, testContext.getUserPhone(), testContext.getUserPhone(), "Test", $(11), "EUR", null,
            "", "EUR", testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);

        given()
            .request().body(json)
            .expect().statusCode(404)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("PartyNotFound"));
    }

    @Test
    public void createInternalPaymentOperation_Error_PaymentExceedsBalance() throws InterruptedException{
        createInternalPaymentOperation_Error_PaymentExceedsBalance("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_PaymentExceedsBalance(String operationType) throws InterruptedException{
        mongoHelper.setBalanceLimit(testContext.getSubscriptionLevel(), $(100));
        BigDecimal balance = paymentsHelper.getAccountAvailableBalance().add($(101));
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", balance, "EUR", paymentsHelper.getPaymentFee(operationType, balance, "EUR"),
            "EUR", testContext.getSC1UserPhone(), "Details");
        String json = new PojoToJson().convert(req);

        given()
            .request().body(json)
            .log().ifValidationFails()
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("AccountHasInsufficientFunds"));

        paymentsHelper.restoreBalanceLimit();
    }

    @Test
    public void createInternalPaymentOperation_Error_WrongOperationType() {
        createInternalPaymentOperation_Error_WrongOperationType("SWIFT");
    }

    public void createInternalPaymentOperation_Error_WrongOperationType(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", testContext.getOperationAmount(), "EUR", null,
            "", "EUR", testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("BadRequest"))
            .body("error.message", containsString("Unsupported operation type"));
    }

    @Test
    public void createInternalPaymentOperation_Error_PayerPhoneMismatch() {
        createInternalPaymentOperation_Error_PayerPhoneMismatch("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_PayerPhoneMismatch(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getSC1UserPhone(), "Test", testContext.getOperationAmount(), "EUR", null,
            "", testContext.getSC1UserPhone(), "Test", "");
        String json = new PojoToJson().convert(req);

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("BadRequest"))
            .body("error.message", containsString("Phone numbers mismatch"));
    }

    @Test
    public void createInternalPaymentOperation_Error_NoPartyId() {
        createInternalPaymentOperation_Error_NoPartyId("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_NoPartyId(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, null, testContext.getUserPhone(), "Test", testContext.getOperationAmount(), "EUR",
            paymentsHelper.getPaymentFee(operationType, testContext.getOperationAmount(), "EUR"), "EUR", testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("BadRequest"))
            .body("error.message", equalTo("Missing party ID."));
    }

    @Test
    public void createInternalPaymentOperation_Error_OperationDateIsLessThanCurrentDate() {
    }

    @Test
    public void createInternalPaymentOperation_Error_NoFeeAmount() {
        createInternalPaymentOperation_Error_NoFeeAmount("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_NoFeeAmount(String operationType) {
        if (paymentsHelper.getPaymentFee(operationType, testContext.getOperationAmount(), "EUR").compareTo(BigDecimal.ZERO) != 0) {
            InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", testContext.getOperationAmount(), "EUR",
                "EUR", testContext.getSC1UserPhone(), "Test");
            String json = new PojoToJson().convert(req);

            given()
                .request().body(json)
                .expect().statusCode(400)
                .when()
                .post(url)
                .then()
                .body(matchesJsonSchemaInClasspath("passwordError.json"))
                .body("error.code", equalTo("InvalidParameter"))
                .body("error.message", equalTo("Specified fee amount does not match service fee amount."));
        }
    }

    @Test
    public void createInternalPaymentOperation_Error_NoFeeCurrency() {
        createInternalPaymentOperation_Error_NoFeeCurrency("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_NoFeeCurrency(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", testContext.getOperationAmount(), "EUR",
            paymentsHelper.getPaymentFee(operationType, testContext.getOperationAmount(), "EUR"), null, testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("InvalidParameter"))
            .body("error.message", equalTo("Fee currency is not specified"));
    }

    @Test
    public void createInternalPaymentOperation_Error_AmountIsNotANumber() {
    }

    @Test
    public void createInternalPaymentOperation_Error_AmountWrongFormat() {
        createInternalPaymentOperation_Error_AmountWrongFormat("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_AmountWrongFormat(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", $(4.444), "EUR",
            paymentsHelper.getPaymentFee(operationType, $(4.444), "EUR"), "EUR", testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("InvalidParameter"))
            .body("error.message", equalTo("amount"));
    }

    @Test
    public void createInternalPaymentOperation_Error_CurrencyCodeNotValid() {
        createInternalPaymentOperation_Error_CurrencyCodeNotValid("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_CurrencyCodeNotValid(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", testContext.getOperationAmount(), "EURf",
            paymentsHelper.getPaymentFee(operationType, testContext.getOperationAmount(), "EUR"), "EUR", testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("InvalidParameter"))
            .body("error.message", containsString("expected a 3-letter ISO-4217 code"));
    }

    @Test
    public void createInternalPaymentOperation_Error_CurrencyCodeNotKnown() {
        createInternalPaymentOperation_Error_CurrencyCodeNotKnown("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_CurrencyCodeNotKnown(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", testContext.getOperationAmount(), "LTL",
            paymentsHelper.getPaymentFee(operationType, testContext.getOperationAmount(), "EUR"), "EUR", testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("UnknownCurrency"))
            .body("error.message", containsString("Unknown currency"));
    }

    @Test
    public void createInternalPaymentOperation_Error_BadFeeAmount() {
        createInternalPaymentOperation_Error_BadFeeAmount("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_BadFeeAmount(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", testContext.getOperationAmount(), "EUR", testContext.getOperationAmount(),
            "EUR", testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/operations";

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("InvalidParameter"))
            .body("error.message", equalTo("Specified fee amount does not match service fee amount."));
    }

    @Test
    public void createInternalPaymentOperation_Error_SamePayerAndBeneficiaryPhoneNumbers() {
        createInternalPaymentOperation_Error_SamePayerAndBeneficiaryPhoneNumbers("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_SamePayerAndBeneficiaryPhoneNumbers(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", testContext.getOperationAmount(), "EUR",
            paymentsHelper.getPaymentFee(operationType, testContext.getOperationAmount(), "EUR"), "EUR", testContext.getUserPhone(), "Test");
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/operations";

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("CreditAndDebitAccountsAreTheSame"))
            .body("error.message", equalTo("Credit and debit accounts are the same"));
    }

    //@Test
    public void createInternalPaymentOperation_Error_BeneficiaryNameNotSpecified() {
        createInternalPaymentOperation_Error_BeneficiaryNameNotSpecified("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_BeneficiaryNameNotSpecified(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), null, testContext.getOperationAmount(), "EUR",
            paymentsHelper.getPaymentFee(operationType, testContext.getOperationAmount(), "EUR"), "EUR", testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/operations";

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("BeneficiaryNameNotSpecified"))
            .body("error.message", equalTo("Beneficiary name is not specified."));
    }

    @Test
    public void createInternalPaymentOperation_Error_InvalidBeneficiaryPhoneNumber() {
        createInternalPaymentOperation_Error_InvalidBeneficiaryPhoneNumber("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_InvalidBeneficiaryPhoneNumber(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", testContext.getOperationAmount(), "EUR",
            paymentsHelper.getPaymentFee(operationType, testContext.getOperationAmount(), "EUR"), "EUR", "899999999", "Test");
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/operations";

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("InvalidParameter"))
            .body("error.message", equalTo("Beneficiary phone number invalid format."));
    }

    @Test
    public void createInternalPaymentOperation_Error_PayerAccountNotActive() throws InterruptedException {
        createInternalPaymentOperation_Error_PayerAccountNotActive("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_PayerAccountNotActive(String operationType) throws InterruptedException {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", testContext.getOperationAmount(), "EUR",
            testContext.getOperationAmount(), "EUR", testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/operations";

        parties.unsubscribe(testContext.getUserPhone());

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("MobilePaymentsSubscriptionIllegalStatus"));

        parties.activateSubscription(testContext.getUserPhone());
    }

    @Test
    public void createInternalPaymentOperation_Error_InstructionIdExceedsMaxSymbols() {
        createInternalPaymentOperation_Error_InstructionIdExceedsMaxSymbols("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_InstructionIdExceedsMaxSymbols(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(),
            "Test", testContext.getOperationAmount(), "EUR", testContext.getOperationAmount(), "EUR", testContext.getSC1UserPhone(), "Test", "",
            "We can getClosedAccount the constructor of a class using the same techniques we have used in our previous examples. Sometimes we need to initialize the objects and we do them in a constructor");
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/operations";

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("InvalidParameter"))
            .body("error.message", equalTo("Instruction ID value length."));
    }

    //@Test
    public void createInternalPaymentOperation_Error_BeneficiaryNameExceedsMaxSymbols() {
        createInternalPaymentOperation_Error_BeneficiaryNameExceedsMaxSymbols("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_BeneficiaryNameExceedsMaxSymbols(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "We can getClosedAccount the constructor of a class using the same techniques we have used in our previous examples. Sometimes we need to initialize the objects and we do them in a constructor", testContext.getOperationAmount(), "EUR", paymentsHelper.getPaymentFee(operationType, testContext.getOperationAmount(), "EUR"), "EUR", testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/operations";

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("InvalidParameter"))
            .body("error.message", equalTo("Beneficiary name value length."));
    }

    @Test
    public void createInternalPaymentOperation_Error_PaymentCodeExceedsMaxSymbols() {
        createInternalPaymentOperation_Error_PaymentCodeExceedsMaxSymbols("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_PaymentCodeExceedsMaxSymbols(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", testContext.getOperationAmount(), "EUR", paymentsHelper.getPaymentFee(operationType, testContext.getOperationAmount(), "EUR"), "EUR", testContext.getSC1UserPhone(), "Test", LocalDate.of( 2020 , 1 , 1 ).toString(), "", "We can getClosedAccount the constructor of a class using the same techniques we have used in our previous examples. Sometimes we need to initialize the objects and we do them in a constructor");
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/operations";

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("passwordError.json"))
            .body("error.code", equalTo("InvalidParameter"))
            .body("error.message", equalTo("Payment code value length."));
    }

    @Test
    public void createInternalPaymentOperation_Error_RequiredCompetenceLevel_Average_MobilePaymentsOperationNotAllowed() throws InterruptedException {
        parties.createUser("LOW");
        paymentsHelper.topUp($(5));
        createInternalPaymentOperation_Error_RequiredCompetenceLevel_Average_MobilePaymentsOperationNotAllowed_Test();
    }

    public void createInternalPaymentOperation_Error_RequiredCompetenceLevel_Average_MobilePaymentsOperationNotAllowed_Test() {
        InternalOperationRequest req = new InternalOperationRequest("INTERNAL", getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", $(0.01), "EUR",
            paymentsHelper.getPaymentFee("INTERNAL", $(1), "EUR"), "EUR", testContext.getSC2UserPhone(), "Test");
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/operations";

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body("error.requiredComplianceLevel", equalTo("AVERAGE"))
            .body("error.code", equalTo("MobilePaymentsOperationNotAllowed"));
    }

    @Test
    public void createInternalPaymentOperation_Error_RequiredCompetenceLevel_Top_OperationLimit() throws InterruptedException {
        parties.updateContacts();
        parties.updateAddress();
        parties.updatePep();
        parties.addSubscriptionIdentification("IDENTIFICATION_BY_MNO", true);
        paymentsHelper.topUp($(5));
        createInternalPaymentOperation_Error_RequiredCompetenceLevel_Top_OperationLimit("INTERNAL");
    }

    public void createInternalPaymentOperation_Error_RequiredCompetenceLevel_Top_OperationLimit(String operationType) throws InterruptedException {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", mongoHelper.getOperationLimit(parties.getSubscriptionInfo()).add(BigDecimal.ONE), "EUR",
            paymentsHelper.getPaymentFee(operationType, mongoHelper.getOperationLimit(parties.getSubscriptionInfo()).add(BigDecimal.ONE), "EUR"), "EUR", testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/operations";

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body("error.requiredComplianceLevel", equalTo("TOP"))
            .body("error.code", equalTo("MobilePaymentsOperationLimit"));
    }

    @Test
    public void createInternalPaymentOperation_Error_RequiredCompetenceLevel_Top_DailyLimit() throws InterruptedException {
        mongoHelper.setDailyDebitTurnoverLimit(testContext.getSubscriptionLevel(), $(45));
        try {
            createInternalPaymentOperation_Error_RequiredCompetenceLevel_Top_DailyLimit("INTERNAL");
        } finally {
            mongoHelper.restoreDailyDebitTurnoverLimit(testContext.getSubscriptionLevel());
        }
    }

    public void createInternalPaymentOperation_Error_RequiredCompetenceLevel_Top_DailyLimit(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", mongoHelper.getDailyDebitTurnoverLimit(testContext.getSubscriptionLevel()).add(BigDecimal.ONE), "EUR",
            paymentsHelper.getPaymentFee(operationType, mongoHelper.getDailyDebitTurnoverLimit(testContext.getSubscriptionLevel()).add(BigDecimal.ONE), "EUR"), "EUR", testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/operations";

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body("error.requiredComplianceLevel", equalTo("TOP"))
            .body("error.code", equalTo("MobilePaymentsDailyDebitTurnover"));
    }

    @Test
    public void createInternalPaymentOperation_Error_SubscriptionCreditUseNotAllowed() throws InterruptedException {
        parties.setCarrierBilling(testContext.getUserPhone(), false);
        createInternalPaymentOperation_Error_SubscriptionCreditUseNotAllowed("INTERNAL");
        parties.setCarrierBilling(testContext.getUserPhone(), true);
    }

    public void createInternalPaymentOperation_Error_SubscriptionCreditUseNotAllowed(String operationType) {
       InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", mongoHelper.getUserBalance(testContext.getIBAN()).add(BigDecimal.ONE), "EUR",
            paymentsHelper.getPaymentFee(operationType, mongoHelper.getUserBalance(testContext.getIBAN()).add(BigDecimal.ONE), "EUR"), "EUR", testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/operations";

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body("error.code", equalTo("SubscriptionCreditUseNotAllowed"));
    }

    @Test
    public void createInternalPaymentOperation_Error_RequiredCompetenceLevel_Top_MonthlyLimit() throws InterruptedException {
        mongoHelper.setMonthlyDebitTurnoverLimit(testContext.getSubscriptionLevel(), $(35));
        try {
            createInternalPaymentOperation_Error_RequiredCompetenceLevel_Top_MonthlyLimit("INTERNAL");
        } finally {
            mongoHelper.restoreMonthlyDebitTurnoverLimit(testContext.getSubscriptionLevel());
        }
    }

    public void createInternalPaymentOperation_Error_RequiredCompetenceLevel_Top_MonthlyLimit(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", mongoHelper.getMonthlyDebitTurnoverLimit(testContext.getSubscriptionLevel()).add(BigDecimal.ONE), "EUR",
            paymentsHelper.getPaymentFee(operationType, mongoHelper.getMonthlyDebitTurnoverLimit(testContext.getSubscriptionLevel()).add(BigDecimal.ONE), "EUR"), "EUR", testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/operations";

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body("error.requiredComplianceLevel", equalTo("TOP"))
            .body("error.code", equalTo("MobilePaymentsMonthlyDebitTurnover"));
    }

    @Test
    public void createInternalPaymentOperation_Error_RequiredCompetenceLevel_Top_YearlyLimit() throws InterruptedException {
        mongoHelper.setYearlyDebitTurnoverLimit(testContext.getSubscriptionLevel(), $(25));
        try {
            createInternalPaymentOperation_Error_RequiredCompetenceLevel_Top_YearlyLimit("INTERNAL");
        } finally {
            mongoHelper.restoreYearlyDebitTurnoverLimit(testContext.getSubscriptionLevel());
        }
    }

    public void createInternalPaymentOperation_Error_RequiredCompetenceLevel_Top_YearlyLimit(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test", mongoHelper.getYearlyDebitTurnoverLimit(testContext.getSubscriptionLevel()).add(BigDecimal.ONE), "EUR",
            paymentsHelper.getPaymentFee(operationType, mongoHelper.getYearlyDebitTurnoverLimit(testContext.getSubscriptionLevel()).add(BigDecimal.ONE), "EUR"), "EUR", testContext.getSC1UserPhone(), "Test");
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/operations";

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body("error.requiredComplianceLevel", equalTo("TOP"))
            .body("error.code", equalTo("MobilePaymentsYearlyDebitTurnover"));
    }

    @Test
    public void createInternalPaymentOperation_Error_OnboardingPaymentAmountLimitExceeded() throws InterruptedException {
        parties.updateContacts();
        parties.updateAddress();
        parties.updatePep();
        parties.updateKyc();
        parties.addSubscriptionIdentification("M_SIGNATURE", true);
        mongoHelper.setOnboardingLimit(Amount.of($(5),"EUR"));
        paymentsHelper.topUp($(50));
        try {
            createInternalPaymentOperation_Error_OnboardingPaymentAmountLimitExceeded("INTERNAL");
        } finally {
            mongoHelper.restoreOnboardingLimit();
        }
    }

    public void createInternalPaymentOperation_Error_OnboardingPaymentAmountLimitExceeded(String operationType) {
        InternalOperationRequest req = new InternalOperationRequest(operationType, getPartyIdFromPhone(testContext.getUserPhone()), testContext.getUserPhone(), "Test",mongoHelper.getOnboardingLimit().add(BigDecimal.ONE), "EUR",
            paymentsHelper.getPaymentFee(operationType, mongoHelper.getOnboardingLimit().add(BigDecimal.ONE), "EUR"), "EUR", getRandomPhoneNumber(), "Test");
        String json = new PojoToJson().convert(req);
        String url = "/rest/mobile/operations";

        given()
            .request().body(json)
            .expect().statusCode(400)
            .when()
            .post(url)
            .then()
            .body("error.code", equalTo("OnboardingPaymentAmountLimitExceeded"));
    }
}