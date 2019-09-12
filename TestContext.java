package lt.bas.nano.service.rest.mobilepayments;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.testng.ITestContext;

import java.math.BigDecimal;

import static lt.bas.nano.service.rest.mobilepayments.payments.PaymentsHelper.waitUntil;

@Singleton
public class TestContext {
    @Inject
    ITestContext testContext;

    public void setPartyId(String partyId){
        testContext.getSuite().setAttribute(Parameters.PARTY_ID, partyId);
    }

    public String getPartyId(){
        return (String) testContext.getSuite().getAttribute(Parameters.PARTY_ID);
    }

    public void setUserPhone(String phoneNumber) throws InterruptedException {
        testContext.getSuite().setAttribute(Parameters.PHONE_NUMBER, phoneNumber);
        waitUntil(() -> phoneNumber.equals(getUserPhone()));
    }

    public String getUserPhone(){
        return (String) testContext.getSuite().getAttribute(Parameters.PHONE_NUMBER);
    }

    public void setOriginalPhone(String phoneNumber) throws InterruptedException {
        testContext.getSuite().setAttribute(Parameters.ORIGINAL_PHONE, phoneNumber);
        waitUntil(() -> phoneNumber.equals(getOriginalPhone()));
    }

    public String getOriginalPhone(){
        return (String) testContext.getSuite().getAttribute(Parameters.ORIGINAL_PHONE);
    }

    public void setPersonCode(String code) throws InterruptedException {
        testContext.getSuite().setAttribute(Parameters.PERSON_CODE, code);
        waitUntil(() -> code.equals(getPersonCode()));
    }

    public String getPersonCode(){
        return (String) testContext.getSuite().getAttribute(Parameters.PERSON_CODE);
    }

    public void setSubscriptionLevel(String subscriptionLevel) throws InterruptedException {
        testContext.getSuite().setAttribute(Parameters.SUBSCRIPTION_LEVEL, subscriptionLevel);
        waitUntil(() -> subscriptionLevel.equals(getSubscriptionLevel()));
    }

    public String getSubscriptionLevel(){
        return (String) testContext.getSuite().getAttribute(Parameters.SUBSCRIPTION_LEVEL);
    }

    public void setIBAN(String iban) {
        testContext.getSuite().setAttribute(Parameters.IBAN, iban);
    }

    public String getIBAN(){
        return (String) testContext.getSuite().getAttribute(Parameters.IBAN);
    }

    public void setOperationId(String operationId) throws InterruptedException {
        testContext.getSuite().setAttribute(Parameters.OPERATION_ID, operationId);
        waitUntil(() -> operationId.equals(getOperationId()));
    }

    public String getOperationId(){
        return (String) testContext.getSuite().getAttribute(Parameters.OPERATION_ID);
    }

    public void setFirstMerchantAccount(String account){
        testContext.getSuite().setAttribute(Parameters.FIRST_MERCHANT_ACCOUNT, account);
    }

    public String getFirstMerchantAccount(){
        return (String) testContext.getSuite().getAttribute(Parameters.FIRST_MERCHANT_ACCOUNT);
    }

    public void setSecondMerchantAccount(String account){
        testContext.getSuite().setAttribute(Parameters.SECOND_MERCHANT_ACCOUNT, account);
    }

    public String getSecondMerchantAccount(){
        return (String) testContext.getSuite().getAttribute(Parameters.SECOND_MERCHANT_ACCOUNT);
    }

    public void setMgOperationId(String operationId){
        testContext.getSuite().setAttribute(Parameters.MG_OPERATION_ID, operationId);
    }

    public String getMgOperationId(){
        return (String) testContext.getSuite().getAttribute(Parameters.MG_OPERATION_ID);
    }

    public void setEndToEndIdentification(String id){
        testContext.getSuite().setAttribute(Parameters.END_TO_END_IDENTIFICATION, id);
    }

    public String getEndToEndIdentification(){
        return (String) testContext.getSuite().getAttribute(Parameters.END_TO_END_IDENTIFICATION);
    }

    public void setTransferId(String transferId){
        testContext.getSuite().setAttribute(Parameters.TRANSFER_ID, transferId);
    }

    public String getTransferId(){
        return (String) testContext.getSuite().getAttribute(Parameters.TRANSFER_ID);
    }

    public void setPaymentConfirmationType(String type){
        testContext.getSuite().setAttribute(Parameters.PAYMENT_CONFIRMATION_TYPE, type);
    }

    public String getPaymentConfirmationType(){
        return (String) testContext.getSuite().getAttribute(Parameters.PAYMENT_CONFIRMATION_TYPE);
    }

    public void setPaymentCode(String code){
        testContext.getSuite().setAttribute(Parameters.PAYMENT_CODE, code);
    }

    public String getPaymentCode(){
        return (String) testContext.getSuite().getAttribute(Parameters.PAYMENT_CODE);
    }

    public void setPaymentEntry(String paymentEntry){
        testContext.getSuite().setAttribute(Parameters.PAYMENT_ENTRY, paymentEntry);
    }

    public String getPaymentEntry(){
        return (String) testContext.getSuite().getAttribute(Parameters.PAYMENT_ENTRY);
    }

    public void setPaymentRequestId(String paymentRequestId){
        testContext.getSuite().setAttribute(Parameters.PAYMENT_REQUEST_ID, paymentRequestId);
    }

    public String getPaymentRequestId(){
        return (String) testContext.getSuite().getAttribute(Parameters.PAYMENT_REQUEST_ID);
    }

    public void setInternalAccount(String account){
        testContext.getSuite().setAttribute(Parameters.INTERNAL_ACCOUNT, account);
    }

    public String getInternalAccount(){
        return (String) testContext.getSuite().getAttribute(Parameters.INTERNAL_ACCOUNT);
    }

    public void setProvider(String provider){
        testContext.getSuite().setAttribute(Parameters.PROVIDER, provider);
    }

    public String getProvider(){
        return (String) testContext.getSuite().getAttribute(Parameters.PROVIDER);
    }

    public void setProviderMonthlyLimit(BigDecimal limit){
        testContext.getSuite().setAttribute(Parameters.PROVIDER_MONTHLY_LIMIT, limit);
    }

    public BigDecimal getProviderMonthlyLimit(){
        return (BigDecimal) testContext.getSuite().getAttribute(Parameters.PROVIDER_MONTHLY_LIMIT);
    }

    public void setProviderCreditAllowed(Boolean allowed){
        testContext.getSuite().setAttribute(Parameters.PROVIDER_CREDIT_ALLOWED, allowed);
    }

    public Boolean getProviderCreditAllowed(){
        return (Boolean) testContext.getSuite().getAttribute(Parameters.PROVIDER_CREDIT_ALLOWED);
    }

    public void setProviderCurrency(String currency){
        testContext.getSuite().setAttribute(Parameters.PROVIDER_CURRENCY, currency);
    }

    public String getProviderCurrency(){
        return (String) testContext.getSuite().getAttribute(Parameters.PROVIDER_CURRENCY);
    }

    public void setSC1UserPhone(String phone){
        testContext.getSuite().setAttribute(Parameters.SC1_USER, phone);
    }

    public String getSC1UserPhone(){
        return (String) testContext.getSuite().getAttribute(Parameters.SC1_USER);
    }

    public void setSC2UserPhone(String phone){
        testContext.getSuite().setAttribute(Parameters.SC2_USER, phone);
    }

    public String getSC2UserPhone(){
        return (String) testContext.getSuite().getAttribute(Parameters.SC2_USER);
    }

    public void setFCUserPhone(String phone){
        testContext.getSuite().setAttribute(Parameters.FC_USER, phone);
    }

    public String getFCUserPhone(){
        return (String) testContext.getSuite().getAttribute(Parameters.FC_USER);
    }

    public void setSubscriptionPassword(String password){
        testContext.getSuite().setAttribute(Parameters.SUBSCRIPTION_PASSWORD, password);
    }

    public String getSubscriptionPassword(){
        return (String) testContext.getSuite().getAttribute(Parameters.SUBSCRIPTION_PASSWORD);
    }

    public void setOperationAmount(BigDecimal amount){
        testContext.getSuite().setAttribute(Parameters.OPERATION_AMOUNT, amount);
    }

    public BigDecimal getOperationAmount(){
        return (BigDecimal) testContext.getSuite().getAttribute(Parameters.OPERATION_AMOUNT);
    }
}