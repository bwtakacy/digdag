package io.digdag.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import com.google.common.base.Optional;
import com.google.inject.Inject;
import io.digdag.client.config.Config;
import io.digdag.server.ServerSecretAccessPolicy.OperatorSecretAccessPolicy;
import io.digdag.spi.SecretAccessContext;
import io.digdag.spi.SecretAccessPolicy;
import io.digdag.spi.SecretSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultSecretAccessPolicy
        implements SecretAccessPolicy
{
    private static final Logger logger = LoggerFactory.getLogger(DefaultSecretAccessPolicy.class);

    private final ServerSecretAccessPolicy policy;
    private final Boolean enabled;

    @Inject
    public DefaultSecretAccessPolicy(Config systemConfig, ObjectMapper mapper)
            throws IOException
    {
        ServerSecretAccessPolicy.Builder builder;
        Optional<String> policyFilename = systemConfig.getOptional("digdag.secret-access-policy-file", String.class);
        if (policyFilename.isPresent()) {
            this.enabled = true;
            builder = ServerSecretAccessPolicy.builder().from(readPolicy(mapper, Paths.get(policyFilename.get())));
        }
        else {
            this.enabled = systemConfig.get("secret-access-policy.enabled", Boolean.class, false);
            builder = ServerSecretAccessPolicy.builder();
        }

        Pattern pattern = Pattern.compile("^secret-access-policy\\.operators\\.(\\w+)\\.secrets$");

        for (String key : systemConfig.getKeys()) {
            Matcher m = pattern.matcher(key);
            if (m.find()) {
                String operatorType = m.group(1);
                String value = systemConfig.get(key, String.class);
                List<SecretSelector> selectors = mapper.readValue(value, new TypeReference<List<SecretSelector>>() {});
                builder.putOperators(operatorType, OperatorSecretAccessPolicy.of(selectors));
            }
        }

        this.policy = builder.build();

        logger.info("loaded secret access policy: {} (enabled: {})", policy, enabled);
    }

    @Override
    public boolean isSecretAccessible(SecretAccessContext context, String key)
    {
        if (!enabled) {
            return true;
        }

        if (policy == null) {
            return false;
        }

        OperatorSecretAccessPolicy operatorPolicy = this.policy.operators().get(context.operatorType());
        if (operatorPolicy == null) {
            return false;
        }

        for (SecretSelector selector : operatorPolicy.secrets()) {
            if (selector.match(key)) {
                return true;
            }
        }

        return false;
    }

    private static ServerSecretAccessPolicy readPolicy(ObjectMapper mapper, Path path)
            throws IOException
    {
        try (InputStream in = Files.newInputStream(path)) {
            return readPolicy(mapper, in);
        }
    }

    private static ServerSecretAccessPolicy readPolicy(ObjectMapper mapper, InputStream in)
            throws IOException
    {
        YAMLParser parser = new YAMLFactory().createParser(in);
        return mapper.readValue(parser, ServerSecretAccessPolicy.class);
    }
}
