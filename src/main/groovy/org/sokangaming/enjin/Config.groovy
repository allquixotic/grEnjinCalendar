package org.sokangaming.enjin

import groovy.json.JsonSlurper
import groovy.transform.Canonical

@Canonical
class Config {
    String loginUrl
    Map<String, String> calendarUrls
    String discordSecret
    Map<String, String> discordChannels
    String enjinUsername
    String enjinPassword
    String proxyListApi
    String proxyUsername
    String proxyPassword
    String curlPath
    String silent
    String[] curlExtra
    String testUrl
    int proxyPort
    int updateFrequency
    int maxLoadFactor
    static Config global = null
    private static final String DEFAULT_CONFIG_FILE = "config.json"

    static Config readConfig(String file = "config.json") throws IOException {
        new JsonSlurper().parse(file ? new File(file) : new File(DEFAULT_CONFIG_FILE)) as Config
    }
}
