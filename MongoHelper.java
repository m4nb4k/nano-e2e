package lt.bas.nano.service.rest.mobilepayments.payments;

import com.google.inject.Inject;
import lt.bas.nano.backoffice.product.mobilepayments.ManagedMobilePaymentsProduct;
import lt.bas.nano.common.domain.Amount;
import lt.bas.nano.common.domain.AmountRange;
import lt.bas.nano.mobilepayments.MobilePaymentsConfirmationType;
import lt.bas.nano.mobilepayments.MobilePaymentsOperation;
import lt.bas.nano.mobilepayments.MobilePaymentsProvider;
import lt.bas.nano.mobilepayments.TopUpServiceProvider;
import lt.bas.nano.mobilepayments.product.TopUpLimits;
import lt.bas.nano.query.currentaccount.CurrentAccount;
import lt.bas.nano.query.currentaccount.CustomerAvailableFunds;
import lt.bas.nano.query.mobilepayments.MobilePaymentsContract;
import lt.bas.nano.query.payment.Payment;
import lt.bas.nano.service.rest.mobilepayments.TestContext;
import lt.bas.nano.service.rest.mobilepayments.subscription.requests.CrmParty;
import lt.bas.nano.service.rest.mobilepayments.subscription.requests.MobilePaymentsSubscription;
import lt.bas.nano.service.rest.mobilepayments.topUp.IbOperation;
import lt.bas.nano.service.rest.mobilepayments.utils.MongoConfiguration;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;
import org.testng.annotations.Guice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static lt.bas.nano.mobilepayments.MobilePaymentsOperationType.PAYMENT;
import static lt.bas.nano.mobilepayments.MobilePaymentsOperationType.REQUEST;
import static lt.bas.nano.test.domain.NanoBasicTypesTestHelper.$;
import static lt.bas.nano.service.rest.mobilepayments.payments.PaymentsHelper.i;
import static lt.bas.nano.service.rest.mobilepayments.payments.PaymentsHelper.waitCount;
import static lt.bas.nano.service.rest.mobilepayments.subscription.requests.GetMissingDataForUpgrade.getSettingsNo;
import static lt.bas.nano.service.rest.mobilepayments.utils.MongoConfiguration.nanoBackOffice;
import static lt.bas.nano.service.rest.mobilepayments.utils.MongoConfiguration.nanoCompliance;
import static lt.bas.nano.service.rest.mobilepayments.utils.MongoConfiguration.nanoProjections;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.limit;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.skip;
import static org.springframework.data.mongodb.core.query.Criteria.where;

@Guice
public class MongoHelper {
    @Inject
    TestContext testContext;
    @Inject
    PaymentsHelper paymentsHelper;

    public String getBlacklistKeyword() {
        return nanoCompliance().findOne(new Query(where("entryType").is("SDN")), KeywordEntry.class, "keywordEntry").keyword;
    }

    public class KeywordEntry {
        public String _id;
        public String entryType;
        public String keyword;
        public String type;

        public KeywordEntry(String _id, String entryType, String keyword, String type) {
            this._id = _id;
            this.entryType = entryType;
            this.keyword = keyword;
            this.type = type;
        }
    }

    public String getFailedIbOperation() {
        return nanoProjections().findOne(new Query(where("status").is("FAILED")), IbOperation.class, "ibOperation")._id;
    }

    public static String getPhoneProvider(String phone) {
        try {
            String statusUrl = "/rest/mobile/contracts/subscriptions/" + phone;
            return given()
                .expect().statusCode(200)
                .when()
                .get(statusUrl)
                .then()
                .extract().path("provider");
        }

        catch(AssertionError e) {
            return "TELE2";
        }
    }

    public void switchToOperationInitiator(String operationId) throws InterruptedException {
        String initiatorsPhone = getOperationInitiatorsPhone(operationId);

        if (testContext.getUserPhone().equals(initiatorsPhone)) {
            return;
        } else {
            paymentsHelper.changePhoneInHeader(initiatorsPhone);
        }
    }

    public static List<IbOperation> getUnfinishedOperationsList() {
        return nanoProjections().find(new Query(where("beneficiaryName").is("Selenium Liao").orOperator(where("payerName").is("Selenium Liao")).and("status").is("INITIATED")), IbOperation.class, "ibOperation");
    }

    public static String getOperationInitiatorsPartyId(String operationId) {
        return nanoProjections().findOne(new Query(where("_id").is(operationId)), IbOperation.class, "ibOperation").partyId;
    }

    public static String getOperationInitiatorsPhone(String operationId) {
        return getPhoneFromPartyId(getOperationInitiatorsPartyId(operationId));
    }

    public BigDecimal getAccountBalanceLimit() {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        return product.complianceLevelSettings.get(getSettingsNo(testContext.getSubscriptionLevel())).balanceLimit;
    }

    public static String getInactiveAccount() {
        Instant instant = Instant.now().minus(Duration.ofDays(1));
        return String.valueOf(nanoProjections().findOne(new Query(where("status").is("CLOSED").and("openedOn").lte(instant).and("holder").exists(true)), CurrentAccount.class, "currentAccount"));
    }

    public static String getPhoneFromIban(String iban) {
        return nanoProjections().findOne(new Query(where("accounts._id").is(iban)), lt.bas.nano.service.rest.mobilepayments.subscription.requests.MobilePaymentsSubscription.class, "mobilePaymentsSubscription").principals.get("_id").toString();
    }

    public static String getProviderCreditAccount(String provider) {
        return nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct").providersSubscriptionCreditAccounts().get(new MobilePaymentsProvider(provider));
    }

    public static String getAccountFromPhone(String phone) {
        List<lt.bas.nano.service.rest.mobilepayments.subscription.requests.CurrentAccount> account = nanoProjections().find(new Query(where("holder").is(getPartyIdFromPhone(phone)).and("status").is("ACTIVE")), lt.bas.nano.service.rest.mobilepayments.subscription.requests.CurrentAccount.class, "currentAccount");
        if(account.isEmpty()) {
            return null;
        }
        return account.get(0)._id;
    }

    public static String getPhoneFromPartyId(String partyId) {
        return nanoProjections().findOne(new Query(where("partyId").is(partyId)), lt.bas.nano.service.rest.mobilepayments.subscription.requests.MobilePaymentsSubscription.class, "mobilePaymentsSubscription").principals.get("_id").toString();
    }

    public String getAccountForSepa() {
        return nanoProjections().findOne(new Query(where("operationType").is("SEPA").and("status").is("COMPLETED")), IbOperation.class, "ibOperation").beneficiaryAccountNumber;
    }

    public Boolean contractIsActive(String partyId) {
        MobilePaymentsContract tst = nanoProjections().findOne(new Query(where("partyId").is(partyId)), MobilePaymentsContract.class, "mobilePaymentsContract");
        if (tst.status.name().equals("BLOCKED")) {
            return false;
        }

        return true;
    }

    public String getSubscriptionLevel() {
        MobilePaymentsSubscription subscription = nanoProjections().findOne(new Query(where("partyId").is(getPartyIdFromPhone(testContext.getUserPhone())).and("status").is("ACTIVE")), MobilePaymentsSubscription.class, "mobilePaymentsSubscription");
        if (subscription==null) {
            return "";
        }
        else return subscription.subscriptionComplianceLevel;
    }

    public static String getPartyIdFromPhone(String phone) {
        if (nanoProjections().findOne(new Query(where("principals._id").is(phone)), lt.bas.nano.service.rest.mobilepayments.subscription.requests.MobilePaymentsSubscription.class, "mobilePaymentsSubscription")==null) {
            return "";
        }
        else if (nanoProjections().findOne(new Query(where("principals._id").is(phone).and("status").is("ACTIVE")), lt.bas.nano.service.rest.mobilepayments.subscription.requests.MobilePaymentsSubscription.class, "mobilePaymentsSubscription")==null) {
            return nanoProjections().findOne(new Query(where("principals._id").is(phone).and("status").is("INACTIVE")), lt.bas.nano.service.rest.mobilepayments.subscription.requests.MobilePaymentsSubscription.class, "mobilePaymentsSubscription").partyId;
        }
        return nanoProjections().findOne(new Query(where("principals._id").is(phone).and("status").is("ACTIVE")), lt.bas.nano.service.rest.mobilepayments.subscription.requests.MobilePaymentsSubscription.class, "mobilePaymentsSubscription").partyId;
    }

    public static String getPartyIdFromPersonCode(String personCode) {
        CrmParty crm = nanoProjections().findOne(new Query(where("basicInfo.personCode").is(personCode)), CrmParty.class, "crmParty");
        if (crm==null) {
            return "";
        }
        return crm._id;
    }

    public static String getPartyIdFromIban(String iban) {
        return nanoProjections().findOne(new Query(where("accounts.number").is(iban)), CustomerAvailableFunds.class, "customerAvailableFunds").id;
    }

    public static String getRandomPartyId() {
        return nanoProjections().findOne(new Query(where("basicInfo.personGender").is("MALE")), CrmParty.class, "crmParty")._id;
    }

    public String getAnyPartyId() {
        Long count = nanoProjections().getCollection("crmParty").count();
        String partyId = "";

        while (i < waitCount) {
            Aggregation aggregation = newAggregation(skip(ThreadLocalRandom.current().nextLong(count)), limit(1L));
            AggregationResults<CrmParty> results = nanoProjections().aggregate(aggregation, "crmParty", CrmParty.class);
            i++;
            if (contractIsActive(results.getMappedResults().get(0)._id)) {
                partyId = results.getMappedResults().get(0)._id;
                break;
            }
        }
        return partyId;
    }

    public static BigDecimal getUserBalance(String iban) {
        return nanoProjections().findById(getPartyIdFromIban(iban), CustomerAvailableFunds.class).getAccountAvailableFundsByCurrency(iban, "EUR").value.setScale(2, RoundingMode.HALF_UP);
    }

    public String getAccountBlockedForDebit() {
        return nanoProjections().findOne(new Query(where("operationsBlockType").is("DEBIT")), lt.bas.nano.service.rest.mobilepayments.subscription.requests.CurrentAccount.class, "currentAccount")._id;
    }

    public String getAccountBlockedForCredit() {
        return nanoProjections().findOne(new Query(where("operationsBlockType").is("CREDIT")), lt.bas.nano.service.rest.mobilepayments.subscription.requests.CurrentAccount.class, "currentAccount")._id;
    }

    public String getAccountPartyId(String account) {
        return nanoProjections().findOne(new Query(where("_id").is(account)), lt.bas.nano.service.rest.mobilepayments.subscription.requests.CurrentAccount.class, "currentAccount").openedBy;
    }

    public static String getPersonCodeFromPhone(String phone) {
        CrmParty crm = nanoProjections().findOne(new Query(where("_id").is(getPartyIdFromPhone(phone))), CrmParty.class, "crmParty");
        if (crm==null) {
            return "";
        }
        return crm.basicInfo.personCode;
    }

    public static String getMerchantAccount (String account) {
        return nanoProjections().findOne(new Query(where("product").is("ACC-INT-SAVER-MERCHANT").and("status").is("ACTIVE").and("_id").ne(account)), lt.bas.nano.service.rest.mobilepayments.subscription.requests.CurrentAccount.class, "currentAccount")._id;
    }

    public static String getInternalAccount () {
        return nanoProjections().findOne(new Query(where("nodeType").is("INTERNAL").and("category").is("INCOME")), lt.bas.nano.service.rest.mobilepayments.subscription.requests.CurrentAccount.class, "glAccount")._id;
    }

    public void setRequestScamMinuteLimit(Integer limit) throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.scamPrevention.get(REQUEST).subscriptionPerMinute = limit;
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public void setRequestScamHourLimit(Integer limit) throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.scamPrevention.get(REQUEST).subscriptionPerHour = limit;
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public void restoreRequestScamMinuteLimit() throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.scamPrevention.get(REQUEST).subscriptionPerMinute = 20;
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public void restoreRequestScamHourLimit() throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.scamPrevention.get(REQUEST).subscriptionPerHour = 30;
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public void setPaymentScamMinuteLimit(Integer limit) throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.scamPrevention.get(PAYMENT).subscriptionPerMinute = limit;
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public void setPaymentScamHourLimit(Integer limit) throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.scamPrevention.get(PAYMENT).subscriptionPerHour = limit;
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public void restorePaymentScamMinuteLimit() throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.scamPrevention.get(PAYMENT).subscriptionPerMinute = 1000;
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public void restorePaymentScamHourLimit() throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.scamPrevention.get(PAYMENT).subscriptionPerHour = 60000;
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public BigDecimal getOperationLimit(String level) {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        return product.complianceLevelSettings.get(getSettingsNo(level)).limits.operationLimit;
    }

    public void setSignatureLimit(Enum level, AmountRange till) throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.confirmationLevels.replace((MobilePaymentsConfirmationType) level, till);
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public void setOperationLimit(String level, BigDecimal amount) throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.complianceLevelSettings.get(getSettingsNo(level)).limits.operationLimit = amount;
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public void setYearlyTurnoverLimit(String level, BigDecimal amount) throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.complianceLevelSettings.get(getSettingsNo(level)).limits.turnoverLimit.yearly = amount;
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public BigDecimal getYearlyTurnoverLimit(String level) {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        return product.complianceLevelSettings.get(getSettingsNo(level)).limits.turnoverLimit.yearly;
    }

    public void restoreYearlyTurnoverLimit(String level) throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");

        if (level.equals("AVERAGE")) {
            product.complianceLevelSettings.get(getSettingsNo(level)).limits.turnoverLimit.yearly = $(1000);
        }
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public void setDailyDebitTurnoverLimit(String level, BigDecimal amount) throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.complianceLevelSettings.get(getSettingsNo(level)).limits.debitTurnoverLimit.daily = amount;
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public BigDecimal getDailyDebitTurnoverLimit(String level) {
        return nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct").complianceLevelSettings.get(getSettingsNo(level)).limits.debitTurnoverLimit.daily;
    }

    public void restoreDailyDebitTurnoverLimit(String level) throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        if (level.equals("LOW")) {
            product.complianceLevelSettings.get(getSettingsNo(level)).limits.debitTurnoverLimit.daily = $(150);
        }

        if (level.equals("AVERAGE")) {
            product.complianceLevelSettings.get(getSettingsNo(level)).limits.debitTurnoverLimit.daily = $(1000);
        }

        if (level.equals("TOP")) {
            product.complianceLevelSettings.get(getSettingsNo(level)).limits.debitTurnoverLimit.daily = $(15000);
        }
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public void setMonthlyDebitTurnoverLimit(String level, BigDecimal amount) throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.complianceLevelSettings.get(getSettingsNo(level)).limits.debitTurnoverLimit.monthly = amount;
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public BigDecimal getMonthlyDebitTurnoverLimit(String level) {
        return nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct").complianceLevelSettings.get(getSettingsNo(level)).limits.debitTurnoverLimit.monthly;
    }

    public void restoreMonthlyDebitTurnoverLimit(String level) throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        if (level.equals("LOW")) {
            product.complianceLevelSettings.get(getSettingsNo(level)).limits.debitTurnoverLimit.monthly = $(150);
        }

        if (level.equals("AVERAGE")) {
            product.complianceLevelSettings.get(getSettingsNo(level)).limits.debitTurnoverLimit.monthly = $(1000);
        }

        if (level.equals("TOP")) {
            product.complianceLevelSettings.get(getSettingsNo(level)).limits.debitTurnoverLimit.monthly = $(15000);
        }
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public void setYearlyDebitTurnoverLimit(String level, BigDecimal amount) throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.complianceLevelSettings.get(getSettingsNo(level)).limits.debitTurnoverLimit.yearly = amount;
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public BigDecimal getYearlyDebitTurnoverLimit(String level) {
        return nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct").complianceLevelSettings.get(getSettingsNo(level)).limits.debitTurnoverLimit.yearly;
    }

    public void restoreYearlyDebitTurnoverLimit(String level) throws InterruptedException {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        if (level.equals("LOW")) {
            product.complianceLevelSettings.get(getSettingsNo(level)).limits.debitTurnoverLimit.yearly = $(1800);
        }

        if (level.equals("AVERAGE")) {
            product.complianceLevelSettings.get(getSettingsNo(level)).limits.debitTurnoverLimit.yearly = $(1000);
        }

        if (level.equals("TOP")) {
            product.complianceLevelSettings.get(getSettingsNo(level)).limits.debitTurnoverLimit.yearly = $(15000);
        }
        nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public void setBalanceLimit(String level, BigDecimal amount) throws InterruptedException {
        ManagedMobilePaymentsProduct product = MongoConfiguration.nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.complianceLevelSettings.get(getSettingsNo(level)).balanceLimit = amount;
        MongoConfiguration.nanoBackOffice().save(product);
        Thread.sleep(3000);
    }

    public void setOnboardingLimit(Amount amount) throws InterruptedException {
        ManagedMobilePaymentsProduct product = MongoConfiguration.nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.onboardingPaymentSettings.operationLimit = amount;
        MongoConfiguration.nanoBackOffice().save(product);
        Thread.sleep(3000);
    }

    public BigDecimal getOnboardingLimit() {
        ManagedMobilePaymentsProduct product = MongoConfiguration.nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        return product.onboardingPaymentSettings.operationLimit.value();
    }

    public void restoreOnboardingLimit() throws InterruptedException {
        ManagedMobilePaymentsProduct product = MongoConfiguration.nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        product.onboardingPaymentSettings.operationLimit = Amount.of($(150), "EUR");
        MongoConfiguration.nanoBackOffice().save(product);
        Thread.sleep(2000);
    }

    public TopUpLimits getTopUpLimits(String type) {
        ManagedMobilePaymentsProduct product = MongoConfiguration.nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        return product.topUpLimits.get(getTopUpProvider(type));
    }

    public TopUpServiceProvider getTopUpProvider(String type) {
        switch (type) {
            case "PAYMENTCARD":
                return TopUpServiceProvider.valueOf("PAYMENT_CARD");
            case "NEOPAY":
                return TopUpServiceProvider.valueOf("NEO_PAY");
            case "MISTERTANGO":
                return TopUpServiceProvider.valueOf("MISTER_TANGO");
            default:
                return null;
        }
    }

    public Set<MobilePaymentsOperation> getUserAllowedOperations() {
        ManagedMobilePaymentsProduct product = nanoBackOffice().findById("MOBILE-PAYMENTS", ManagedMobilePaymentsProduct.class, "managedProduct");
        switch (testContext.getSubscriptionLevel()) {
            case "LOW":
                return product.complianceLevelSettings.get(0).allowedOperations;
            case "AVERAGE":
                return product.complianceLevelSettings.get(1).allowedOperations;
            case "TOP":
                return product.complianceLevelSettings.get(2).allowedOperations;
            default:
                return null;
        }
    }

    public String getExistentMerchantPaymentId() {
        return nanoProjections().findOne(new Query(where("endToEndDocumentId").regex("EndToEndId:").and("serviceType").is("MERCHANT_INSTANT_PAYMENT")), Payment.class, "payment").endToEndDocumentId;
    }
}
