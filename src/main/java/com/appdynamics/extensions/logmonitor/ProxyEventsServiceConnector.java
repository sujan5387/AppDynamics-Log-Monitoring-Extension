package com.appdynamics.extensions.logmonitor;

import com.appdynamics.extensions.conf.MonitorContextConfiguration;
import com.google.common.base.Strings;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class ProxyEventsServiceConnector {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyEventsServiceConnector.class);
    private MonitorContextConfiguration configuration;

    public ProxyEventsServiceConnector(MonitorContextConfiguration configuration) {
        this.configuration = configuration;
    }

    public void publishEvents(String schemaName, List<String> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        // 1. Get Config
        Map<String, ?> config = configuration.getConfigYml();
        Map<String, ?> eventsServiceParams = (Map<String, ?>) config.get("eventsServiceParameters");

        if (eventsServiceParams == null) {
            LOGGER.error("eventsServiceParameters not found in config.yml");
            return;
        }

        String host = (String) eventsServiceParams.get("host");
        String port = String.valueOf(eventsServiceParams.get("port")); // Handle int/string safely
        String globalAccountName = (String) eventsServiceParams.get("globalAccountName");
        String apiKey = (String) eventsServiceParams.get("eventsApiKey");
        boolean useSSL = (Boolean) eventsServiceParams.get("useSSL");

        // 2. Setup Client Builder
        HttpClientBuilder clientBuilder = HttpClients.custom();
        
        // --- PROXY SETUP ---
        Map<String, ?> proxyConfig = (Map<String, ?>) eventsServiceParams.get("proxy");
        if (proxyConfig != null) {
            String proxyHost = (String) proxyConfig.get("host");
            Integer proxyPort = (Integer) proxyConfig.get("port");
            String proxyUser = (String) proxyConfig.get("username");
            String proxyPass = (String) proxyConfig.get("password");

            if (!Strings.isNullOrEmpty(proxyHost)) {
                HttpHost proxy = new HttpHost(proxyHost, proxyPort);
                clientBuilder.setProxy(proxy);
                LOGGER.info("Using Proxy: " + proxyHost + ":" + proxyPort);

                if (!Strings.isNullOrEmpty(proxyUser)) {
                    CredentialsProvider credsProvider = new BasicCredentialsProvider();
                    credsProvider.setCredentials(
                            new AuthScope(proxyHost, proxyPort),
                            new UsernamePasswordCredentials(proxyUser, proxyPass)
                    );
                    clientBuilder.setDefaultCredentialsProvider(credsProvider);
                }
            }
        }
        // -------------------

        // 3. Send Data
        try (CloseableHttpClient httpClient = clientBuilder.build()) {
            String protocol = useSSL ? "https" : "http";
            // URL Structure: /events/publish/<GlobalAccountName>/<SchemaName>
            String url = String.format("%s://%s:%s/events/publish/%s/%s",
                    protocol, host, port, globalAccountName, schemaName);

            HttpPost request = new HttpPost(url);
            request.setHeader("X-Events-API-AccountName", globalAccountName);
            request.setHeader("X-Events-API-Key", apiKey);
            request.setHeader("Content-Type", "application/vnd.appd.events+json;v=2");

            // Join events into a JSON array: [ {event1}, {event2} ]
            StringBuilder jsonPayload = new StringBuilder("[");
            for (int i = 0; i < events.size(); i++) {
                jsonPayload.append(events.get(i));
                if (i < events.size() - 1) {
                    jsonPayload.append(",");
                }
            }
            jsonPayload.append("]");

            request.setEntity(new StringEntity(jsonPayload.toString(), StandardCharsets.UTF_8));

            httpClient.execute(request, response -> {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode >= 200 && statusCode < 300) {
                    LOGGER.info("Successfully published {} events to Events Service.", events.size());
                } else {
                    LOGGER.error("Failed to publish events. Status Code: {}", statusCode);
                }
                return null;
            });

        } catch (Exception e) {
            LOGGER.error("Error publishing events via Proxy", e);
        }
    }
}