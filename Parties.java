package lt.bas.nano.service.rest.mobilepayments;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.restassured.RestAssured;
import io.restassured.path.json.config.JsonPathConfig;
import lt.bas.nano.currentaccount.CustomerAvailableFunds;
import lt.bas.nano.service.rest.mobilepayments.carrierBilling.BillingRequest;
import lt.bas.nano.service.rest.mobilepayments.contract.BlockRequest;
import lt.bas.nano.service.rest.mobilepayments.customer.Request;
import lt.bas.nano.service.rest.mobilepayments.password.CreateRequest;
import lt.bas.nano.service.rest.mobilepayments.payments.MongoHelper;
import lt.bas.nano.service.rest.mobilepayments.payments.PaymentsHelper;
import lt.bas.nano.service.rest.mobilepayments.subscription.requests.ChangeProviderRequest;
import lt.bas.nano.service.rest.mobilepayments.subscription.requests.CreationRequest;
import lt.bas.nano.service.rest.mobilepayments.subscription.requests.CrmParty;
import lt.bas.nano.service.rest.mobilepayments.subscription.requests.CurrentAccount;
import lt.bas.nano.service.rest.mobilepayments.subscription.requests.DeletionRequest;
import lt.bas.nano.service.rest.mobilepayments.subscription.requests.GeneralLedgerAccount;
import lt.bas.nano.service.rest.mobilepayments.subscription.requests.GetMissingDataForUpgrade;
import lt.bas.nano.service.rest.mobilepayments.subscription.requests.IdentificationData;
import lt.bas.nano.service.rest.mobilepayments.subscription.requests.IdentificationRequest;
import lt.bas.nano.service.rest.mobilepayments.subscription.requests.MobilePaymentsSubscription;
import lt.bas.nano.service.rest.mobilepayments.updateCustomerData.AddressRequest;
import lt.bas.nano.service.rest.mobilepayments.updateCustomerData.ContactsPatchRequest;
import lt.bas.nano.service.rest.mobilepayments.updateCustomerData.KycRequest;
import lt.bas.nano.service.rest.mobilepayments.updateCustomerData.PepRequest;
import lt.bas.nano.service.rest.mobilepayments.utils.MongoConfiguration;
import lt.bas.nano.service.rest.mobilepayments.utils.PojoToJson;
import org.springframework.data.mongodb.core.query.Query;
import org.testng.Assert;
import org.testng.annotations.Guice;

import java.math.BigDecimal;
import java.util.List;

import static io.restassured.RestAssured.given;
import static io.restassured.config.JsonConfig.jsonConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static lt.bas.nano.test.domain.NanoBasicTypesTestHelper.$;
import static lt.bas.nano.service.rest.mobilepayments.Generator.getRandomPhoneNumber;
import static lt.bas.nano.service.rest.mobilepayments.customer.RegisterPersonService.newRequest;
import static lt.bas.nano.service.rest.mobilepayments.payments.MongoHelper.getAccountFromPhone;
import static lt.bas.nano.service.rest.mobilepayments.payments.MongoHelper.getPartyIdFromPersonCode;
import static lt.bas.nano.service.rest.mobilepayments.payments.MongoHelper.getPartyIdFromPhone;
import static lt.bas.nano.service.rest.mobilepayments.payments.MongoHelper.getPhoneProvider;
import static lt.bas.nano.service.rest.mobilepayments.payments.PaymentsHelper.waitUntil;
import static lt.bas.nano.service.rest.mobilepayments.utils.MongoConfiguration.nanoProjections;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.springframework.data.mongodb.core.query.Criteria.where;

@Guice
@Singleton
public class Parties {
    @Inject
    TestContext testContext;
    @Inject
    BaseTest baseTest;
    @Inject
    PaymentsHelper paymentsHelper;
    @Inject
    Generator generator;

    public void blockContract(String phone) throws InterruptedException {
        String blockUrl = "/rest/mobile/contracts/" + getPartyIdFromPhone(phone) + "/block";

        BlockRequest rq = new BlockRequest("block");
        String json = new PojoToJson().convert(rq);

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .put(blockUrl);

        waitUntil(() -> nanoProjections().exists(new Query(where("partyId").is(getPartyIdFromPhone(phone)).and("status").is("BLOCKED")), MobilePaymentsSubscription.class, "mobilePaymentsContract"));
    }

    public void unblockContract(String phone) throws InterruptedException {
        String blockUrl = "/rest/mobile/contracts/" + getPartyIdFromPhone(phone) + "/block";

        given()
            .expect().statusCode(200)
            .when()
            .delete(blockUrl);

        waitUntil(() -> nanoProjections().exists(new Query(where("partyId").is(getPartyIdFromPhone(phone)).and("status").is("ACTIVE")), MobilePaymentsSubscription.class, "mobilePaymentsContract"));
    }

    public String getSubscriptionInfo() throws InterruptedException {
        String statusUrl = "/rest/mobile/contracts/subscriptions/" + testContext.getUserPhone();

        testContext.setSubscriptionLevel(given()
            .expect().statusCode(200)
            .when()
            .get(statusUrl)
            .then()
            .body("partyId", equalTo(getPartyIdFromPhone(testContext.getUserPhone())))
            .body("provider", equalTo(getPhoneProvider(testContext.getUserPhone())))
            .body("accountNumber", equalTo(getAccountFromPhone(testContext.getUserPhone())))
            .extract().path("subscriptionComplianceLevel"));

        return testContext.getSubscriptionLevel();
    }

    public void checkAccountProduct(String provider) {
        CurrentAccount acc = nanoProjections().findOne(new Query(where("_id").is(testContext.getIBAN())), CurrentAccount.class, "currentAccount");
        acc.product.contains(provider);
    }

    public static String getGeneralLedgerNumber(String provider) {
        GeneralLedgerAccount number = nanoProjections().findOne(new Query(where("name").is(getProvider(provider) + " mokėjimų sąskaitos")), GeneralLedgerAccount.class, "glAccount");
        return number._id;
    }

    private static String getProvider(String provider) {
        switch (provider) {
            case "BITE":
                return ("Bitės");
            case "TELIA":
                return ("Telia");
            case "TELE2":
                return ("Tele2");
            default:
                return null;
        }
    }

    public void checkAccountGeneralLedger(String provider) {
        CurrentAccount acc = nanoProjections().findOne(new Query(where("_id").is(testContext.getIBAN())), CurrentAccount.class, "currentAccount");
        acc.glAccountNumber.contains(getGeneralLedgerNumber(provider));
    }

    public void registration() throws InterruptedException {
        generator.generatePersonalCode();
        registration("/rest/parties/persons", newRequest(testContext.getPersonCode()));
    }

    public void registration(String path, Request registrationRequest) {
        String json = new PojoToJson().convert(registrationRequest);

        testContext.setPartyId(given()
            .request().body(json)
            .expect().statusCode(201)
            .when()
            .post(path)
            .then()
            .body(matchesJsonSchemaInClasspath("registrationSuccess.json"))
            .body("partyId", is(notNullValue()))
            .extract().jsonPath().getString("partyId"));

        baseTest.buildResponse200();
        completeRegistration();
    }

    public void completeRegistration() {
        String completionUrl = "/rest/parties/persons/{partyId}/complete";

        given()
            .pathParam("partyId", testContext.getPartyId())
            .expect().statusCode(200)
            .when()
            .patch(completionUrl);
    }

    public void openAccount() throws InterruptedException {
        String url = "/rest/accounts";

        lt.bas.nano.service.rest.mobilepayments.account.Request rq = new lt.bas.nano.service.rest.mobilepayments.account.Request(getPartyIdFromPersonCode(testContext.getPersonCode()), testContext.getProviderCurrency(), "Test");
        String json = new PojoToJson().convert(rq);

        testContext.setIBAN(given()
            .request().body(json)
            .expect().statusCode(201)
            .when()
            .post(url)
            .then()
            .body(matchesJsonSchemaInClasspath("accountSuccess.json"))
            .body("accountNumber", is(notNullValue()))
            .extract().jsonPath().getJsonObject("accountNumber"));

        waitUntil(() -> MongoConfiguration.nanoProjections().exists(new Query(where("_id").is(getPartyIdFromPersonCode(testContext.getPersonCode())).and("accounts.number").is(testContext.getIBAN())), CustomerAvailableFunds.class));

        Thread.sleep(200);
    }

    public void createContract() {
        String creationUrl = "/rest/mobile/contracts/" + getPartyIdFromPersonCode(testContext.getPersonCode());

        given()
            .expect().statusCode(201)
            .when()
            .post(creationUrl);
    }

    public void createPassword() {
        testContext.setSubscriptionPassword("Admin123");

        String passwordUrl = "/rest/mobile/contracts/" + getPartyIdFromPhone(testContext.getUserPhone()) + "/subscriptions/" + testContext.getUserPhone() + "/password";
        CreateRequest rq = new CreateRequest(testContext.getSubscriptionPassword());
        String json = new PojoToJson().convert(rq);

        given()
            .request().body(json)
            .expect().statusCode(201)
            .when()
            .post(passwordUrl);
    }

    public void subscribeNewPhone() throws InterruptedException {
        String subscriptionUrl = "/rest/mobile/contracts/" + getPartyIdFromPersonCode(testContext.getPersonCode()) + "/subscriptions/" + testContext.getUserPhone();

        CreationRequest rq = new CreationRequest(testContext.getIBAN(), testContext.getProviderCreditAllowed(), testContext.getProviderMonthlyLimit(), testContext.getProviderCurrency());
        String json = new PojoToJson().convert(rq);

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .put(subscriptionUrl);

        waitUntil(() -> nanoProjections().exists(new Query(where("principals._id").is(testContext.getUserPhone()).and("status").is("ACTIVE")), MobilePaymentsSubscription.class, "mobilePaymentsSubscription"));
    }

    public void updateContacts() {
        String updateUrl = "/rest/parties/persons/" + getPartyIdFromPhone(testContext.getUserPhone()) + "/contacts";
        ContactsPatchRequest.Contacts.Emails emailEntries = new ContactsPatchRequest.Contacts.Emails("seleniumtestz@bas.lt");
        ContactsPatchRequest.Contacts.Phones phoneEntries = new ContactsPatchRequest.Contacts.Phones(new ContactsPatchRequest.Contacts.listEntries(testContext.getUserPhone(), "LAND"));
        ContactsPatchRequest rq = new ContactsPatchRequest(new ContactsPatchRequest.Contacts("lt", "selenium@bas.lt", emailEntries, phoneEntries, true));
        String json = new PojoToJson().convert(rq);

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .patch(updateUrl);

        checkUpdatedContacts();
    }

    public void checkUpdatedContacts() {
        CrmParty party = nanoProjections().findOne(new Query(where("basicInfo.personCode").is(testContext.getPersonCode())), CrmParty.class, "crmParty");
        party.satisfiedOnboardingRequirements.contains("EMAIL");
    }

    public void updateAddress() {
        String updateUrl = "/rest/parties/persons/" + getPartyIdFromPhone(testContext.getUserPhone()) + "/addresses";

        AddressRequest.Residence re = new AddressRequest.Residence("LT", "Vilniaus m. sav.", "Vilnius", "09235", "Olimpieciu g.", "1", "123");
        AddressRequest.Addresses ad = new AddressRequest.Addresses(re);
        AddressRequest rq = new AddressRequest(ad, true);
        String json = new PojoToJson().convert(rq);

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .put(updateUrl);

        checkUpdatedAddress();
    }

    public void checkUpdatedAddress() {
        CrmParty party = nanoProjections().findOne(new Query(where("basicInfo.personCode").is(testContext.getPersonCode())), CrmParty.class, "crmParty");
        party.satisfiedOnboardingRequirements.contains("ADDRESS");
    }

    public void updatePep() throws InterruptedException {
        String pepUrl = "/rest/parties/persons/" + getPartyIdFromPhone(testContext.getUserPhone()) + "/pep";

        PepRequest rq = new PepRequest(false);
        String json = new PojoToJson().convert(rq);

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .put(pepUrl);

       checkUpdatedPep();
    }

    public void checkUpdatedPep() throws InterruptedException {
        waitUntil(() -> nanoProjections().findOne(new Query(where("_id").is(getPartyIdFromPhone(testContext.getUserPhone()))), CrmParty.class).satisfiedOnboardingRequirements.contains("NON_POLITICALLY_EXPOSED"));
    }

    public void updateKyc() throws InterruptedException {
        String kycUrl = "/rest/parties/persons/" + getPartyIdFromPhone(testContext.getUserPhone()) + "/kyc";

        KycRequest rq = new KycRequest("STUDENT", "SALARY", "BAS", "QA");
        String json = new PojoToJson().convert(rq);

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .put(kycUrl);

        waitUntil(() -> nanoProjections().findOne(new Query(where("basicInfo.personCode").is(testContext.getPersonCode())), CrmParty.class, "crmParty").satisfiedOnboardingRequirements.contains("KYC"));
    }

    public void setCarrierBilling(String phone, Boolean creditAllowed) throws InterruptedException {
        baseTest.buildResponse200();
        String statusUrl = "/rest/mobile/contracts/" + getPartyIdFromPhone(testContext.getUserPhone()) + "/subscriptions/" + phone + "/creditLimit";

        BillingRequest rq = new BillingRequest(creditAllowed);
        String json = new PojoToJson().convert(rq);

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .put(statusUrl);

        waitUntil(() -> MongoConfiguration.nanoProjections().exists(new Query(where("partyId").is(getPartyIdFromPhone(testContext.getUserPhone())).and("credit.allowed").is(creditAllowed)), MobilePaymentsSubscription.class));
    }

    public void changeProvider(ChangeProviderRequest request) throws InterruptedException {
        String changeUrl = "/rest/mobile/contracts/subscriptions/" + testContext.getUserPhone() + "/provider";

        String json = new PojoToJson().convert(request);

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .patch(changeUrl);

        waitUntil(() -> MongoConfiguration.nanoProjections().exists(new Query(where("partyId").is(getPartyIdFromPhone(testContext.getUserPhone())).and("credit.allowed").is(request.creditAllowed).and("credit.monthlyLimit.value").is(request.creditMonthlyLimit)), MobilePaymentsSubscription.class));

        checkAccountProduct(request.provider);
        //checkAccountGeneralLedger(request.provider);
        getCarrierBilling(testContext.getUserPhone(), request.creditAllowed, request.creditMonthlyLimit);
        testContext.setProvider(request.provider);
        testContext.setProviderCreditAllowed(request.creditAllowed);
        testContext.setProviderMonthlyLimit(request.creditMonthlyLimit);
    }

    public void getCarrierBilling(String phone, Boolean creditAllowed, BigDecimal monthlyLimit) {
        RestAssured.config = newConfig().jsonConfig(jsonConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL));
        String statusUrl = "/rest/mobile/contracts/" + getPartyIdFromPhone(testContext.getUserPhone()) + "/subscriptions/" + phone + "/creditLimit";

        given()
            .expect().statusCode(200)
            .when()
            .get(statusUrl)
            .then()
            .body(matchesJsonSchemaInClasspath("getBillingSuccess.json"))
            .body("allowed", equalTo(creditAllowed))
            .body("usedMonthlyLimit", comparesEqualTo($(0)))
            .body("monthlyLimit", comparesEqualTo(monthlyLimit));
    }

    public void unsubscribe(String phone) throws InterruptedException {
        paymentsHelper.changePhoneInHeader(phone);
        String operator = getPhoneProvider(phone);
        String unsubscribeUrl = "/rest/mobile/contracts/" + MongoHelper.getPartyIdFromPhone(phone) + "/subscriptions/" + phone;
        DeletionRequest rq = new DeletionRequest(operator);
        String json = new PojoToJson().convert(rq);

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .delete(unsubscribeUrl);

        waitUntil(() -> MongoConfiguration.nanoProjections().exists(new Query(where("principals._id").is(testContext.getUserPhone()).and("status").is("INACTIVE")), MobilePaymentsSubscription.class, "mobilePaymentsSubscription"));
        checkInactiveSubscription();
        Thread.sleep(200);
    }

    public void checkInactiveSubscription() {
        String statusUrl = "/rest/mobile/contracts/subscriptions/status/" + testContext.getUserPhone();
        given()
            .expect().statusCode(200)
            .when()
            .get(statusUrl)
            .then()
            .body(matchesJsonSchemaInClasspath("subscriptionStatus.json"))
            .body("status", equalTo("INACTIVE"));
    }

    public void getSubscriptionStatus(String status) {
        String statusUrl = "/rest/mobile/contracts/subscriptions/status/" + testContext.getUserPhone();

        given()
            .expect().statusCode(200)
            .when()
            .get(statusUrl)
            .then()
            .body(matchesJsonSchemaInClasspath("subscriptionStatus.json"))
            .body("partyId", equalTo(getPartyIdFromPhone(testContext.getUserPhone())))
            .body("provider", equalTo(getPhoneProvider(testContext.getUserPhone())))
            .body("status", equalTo(status));
    }

    public void addSubscriptionIdentification(String type, Boolean success) throws InterruptedException {
        String identificationUrl = "/rest/mobile/contracts/" + getPartyIdFromPhone(testContext.getUserPhone()) + "/subscriptions/" + testContext.getUserPhone() + "/identification";

        IdentificationRequest rq = new IdentificationRequest(type, "getClosedAccount", success);
        String json = new PojoToJson().convert(rq);

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .put(identificationUrl)
            .then()
            .body("currentSubscriptionLevel", equalTo(getIdentificationLevel(type)));

        checkUpdatedData(type);
        checkEntryData(type, success);
        getSubscriptionInfo();
    }

    public void checkUpdatedData(String type) {
        MobilePaymentsSubscription data = MongoConfiguration.nanoProjections().findOne(new Query(where("partyId").is(getPartyIdFromPhone(testContext.getUserPhone()))), MobilePaymentsSubscription.class, "mobilePaymentsSubscription");
        data.identifications.contains(type);
    }

    public void checkEntryData(String type, Boolean success) {
        IdentificationData data = MongoConfiguration.nanoIb().findOne(new Query(where("partyId").is(getPartyIdFromPhone(testContext.getUserPhone())).and("identificationType").is(type)), IdentificationData.class, "identificationData");
        Assert.assertTrue(data.identificationType.contains(type));
        Assert.assertTrue(data.success.equals(success));
    }

    public String getIdentificationLevel(String type) {
        List<String> topLvl = GetMissingDataForUpgrade.getAllIdRequirements("TOP");
        List<String> avgLvl = GetMissingDataForUpgrade.getAllIdRequirements("AVERAGE");
        avgLvl.removeAll(topLvl);
        if (avgLvl.stream().anyMatch(str -> str.trim().equals(type))) {
            return "AVERAGE";
        } else if (topLvl.stream().anyMatch(str -> str.trim().equals(type))) {
            return "TOP";
        } else {
            return "type does not exist";
        }
    }

    public String createUser(String level) throws InterruptedException {
        if (level.equals("LOW")) {
            RestAssured.requestSpecification = paymentsHelper.changePhoneInHeader(getRandomPhoneNumber());
            registration();
            openAccount();
            createContract();
            subscribeNewPhone();
            createPassword();
        }

        else if (level.equals("AVERAGE")) {
            RestAssured.requestSpecification = paymentsHelper.changePhoneInHeader(getRandomPhoneNumber());
            registration();
            openAccount();
            createContract();
            subscribeNewPhone();
            createPassword();
            updateContacts();
            updateAddress();
            updatePep();
            addSubscriptionIdentification("IDENTIFICATION_BY_MNO", true);
        }

        else if (level.equals("TOP")) {
            RestAssured.requestSpecification = paymentsHelper.changePhoneInHeader(getRandomPhoneNumber());
            registration();
            openAccount();
            createContract();
            subscribeNewPhone();
            createPassword();
            updateContacts();
            updateAddress();
            updatePep();
            updateKyc();
            addSubscriptionIdentification("M_SIGNATURE", true);
        }

        return testContext.getUserPhone();
    }

    public BigDecimal getAvailableMobileBalance(String phone) {
        String balanceUrl = "rest/mobile/contracts/" + getPartyIdFromPhone(phone) + "/subscriptions/" + phone + "/balance";
        RestAssured.config = newConfig().jsonConfig(jsonConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL));

        Object balance = given()
            .expect().statusCode(200)
            .when()
            .get(balanceUrl)
            .then()
            .extract().path("balances.EUR");

        if (balance instanceof Integer) {
            BigDecimal b = new BigDecimal(String.valueOf(balance));
            return b;
        }

        return given()
            .expect().statusCode(200)
            .when()
            .get(balanceUrl)
            .then()
            .extract().path("balances.EUR");
    }

    public void changeProvider(BigDecimal amount, Boolean creditAllowed) throws InterruptedException {
        if (testContext.getProvider().equals("BITE")) {
            changeProvider(new ChangeProviderRequest("TELIA", amount, creditAllowed, "EUR"));
        }

        else {
            changeProvider(new ChangeProviderRequest("BITE", amount, creditAllowed, "EUR"));
        }
    }

    public void activateSubscription(String phoneNo) throws InterruptedException {
        testContext.setOriginalPhone(testContext.getUserPhone());
        paymentsHelper.changePhoneInHeader(phoneNo);
        String activationUrl = "/rest/mobile/contracts/" + getPartyIdFromPhone(phoneNo) + "/subscriptions/" + phoneNo + "/activate";
        CreationRequest rq = new CreationRequest($(1500), testContext.getProviderCurrency());
        String json = new PojoToJson().convert(rq);

        given()
            .request().body(json)
            .expect().statusCode(200)
            .when()
            .post(activationUrl);

        waitUntil(() -> nanoProjections().exists(new Query(where("partyId").is(getPartyIdFromPhone(phoneNo)).and("status").is("ACTIVE")), MobilePaymentsSubscription.class, "mobilePaymentsSubscription"));
        paymentsHelper.changePhoneInHeader(testContext.getOriginalPhone());
    }
}
