package io.jenkins.plugins.appdome.validate.to.secure;

public interface AppdomeValidateConstants {
    /**
     * Environment variables
     **/
    String APP_PATH = "VALIDATE_APP_PATH";

    String APPDOME_HEADER_ENV_NAME = "APPDOME_CLIENT_HEADER";
    String APPDOME_BUILDE2SECURE_VERSION = "Jenkins/1.2";

    /**
     * FLAGS
     **/
    String KEY_FLAG = " --api_key ";

    String APP_FLAG = " --app ";
    String OUTPUT_FLAG = " --output ";

    String RESULTS_PATH = "results.json";
}
