package lt.bas.nano.service.rest.mobilepayments;

public class Parameters {
    public static String requestContentType = "UTF-8";
    public static Long responseTimeout = 5000L;
    public static String databaseString;
    public static String oAuthBackOfficeToken;

    public static String PARTY_ID = "partyId";
    public static String PHONE_NUMBER = "phone_number";
    public static String ORIGINAL_PHONE = "original_phone";
    public static String PERSON_CODE = "person_code";
    public static String SUBSCRIPTION_LEVEL = "subscription_level";
    public static String IBAN = "iban";
    public static String INTERNAL_ACCOUNT = "internal_account";
    public static String FIRST_MERCHANT_ACCOUNT = "first_merchant_account";
    public static String SECOND_MERCHANT_ACCOUNT = "second_merchant_account";
    public static String OPERATION_ID = "operation_id";
    public static String MG_OPERATION_ID = "mg_operation_id";
    public static String TRANSFER_ID = "transfer_id";
    public static String PAYMENT_REQUEST_ID = "payment_request_id";
    public static String END_TO_END_IDENTIFICATION = "end_to_end_identification";
    public static String PAYMENT_CONFIRMATION_TYPE = "payment_confirmation_type";
    public static String PAYMENT_CODE = "payment_code";
    public static String PAYMENT_ENTRY = "payment_entry";
    public static String PROVIDER = "provider";
    public static String PROVIDER_MONTHLY_LIMIT = "provider_monthly_limit";
    public static String PROVIDER_CREDIT_ALLOWED = "provider_credit_allowed";
    public static String PROVIDER_CURRENCY  = "provider_currency";
    public static String SC1_USER = "sc1_user";
    public static String SC2_USER = "sc2_user";
    public static String FC_USER = "fc_user";
    public static String SUBSCRIPTION_PASSWORD = "subscription_password";
    public static String OPERATION_AMOUNT = "operation_amount";
}