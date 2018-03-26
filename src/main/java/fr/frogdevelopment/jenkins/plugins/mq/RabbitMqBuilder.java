package fr.frogdevelopment.jenkins.plugins.mq;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// cf example https://github.com/jenkinsci/hello-world-plugin
@SuppressWarnings("unused")
public class RabbitMqBuilder extends Builder {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMqBuilder.class);

    private static final Pattern PARAM_PATTERN = Pattern.compile("^\\$\\{?(?<param>\\w+)}?$");

    private final String rabbitName;
    private final String exchange;
    private final String key;
    private final String parameters;

    @DataBoundConstructor
    public RabbitMqBuilder(String rabbitName, String exchange, String key, String parameters) {
        this.rabbitName = rabbitName;
        this.exchange = exchange;
        this.key = key;
        this.parameters = parameters;
    }

    public String getRabbitName() {
        return rabbitName;
    }

    public String getExchange() {
        return exchange;
    }

    public String getKey() {
        return key;
    }

    public String getParameters() {
        return parameters;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        PrintStream console = listener.getLogger();

        try {
            console.println("Initialisation Rabbit-MQ");
            RabbitTemplate rabbitTemplate = getRabbitTemplate();

            console.println("Building message");
            String message = getMessage(build, console);

            LOGGER.info("Sending message as JSON:\n{}", message);
            console.println("Sending message");
            rabbitTemplate.convertAndSend(exchange, key, message);

        } catch (Exception e) {
            LOGGER.error("Error while sending to Rabbit-MQ", e);
            console.println("Error while sending to Rabbit-MQ : " + ExceptionUtils.getMessage(e));

            return false;
        }

        return true;
    }

    private RabbitTemplate getRabbitTemplate() {
        // INIT RABBIT-MQ
        RabbitConfig rabbitConfig = getDescriptor().getRabbitConfig(rabbitName);

        if (rabbitConfig == null) {
            throw new IllegalArgumentException("Unknown rabbit config : " + rabbitName);
        }

        LOGGER.info("Initialisation Rabbit-MQ :\n\t-Host : {}\n\t-Port : {}\n\t-User : {}", rabbitConfig.getHost(), rabbitConfig.getPort(), rabbitConfig.getUsername());

        //
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setAutomaticRecoveryEnabled(false);
        connectionFactory.setUsername(rabbitConfig.getUsername());
        connectionFactory.setPassword(rabbitConfig.getPassword());
        connectionFactory.setHost(rabbitConfig.getHost());
        connectionFactory.setPort(rabbitConfig.getPort());

        //
        RabbitTemplate rabbitTemplate = new RabbitTemplate(new CachingConnectionFactory(connectionFactory));
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());

        return rabbitTemplate;
    }

    private String getMessage(AbstractBuild build, PrintStream console) {
        console.println("Retrieving parameters");
        LOGGER.info("Retrieving parameters :");

        // Retrieving build parameters
        Map<String, String> buildParameters = new HashMap<>();
        ParametersAction parametersAction = build.getAction(ParametersAction.class);
        if (parametersAction != null) {
            // this is a rather round about way of doing this...
            for (ParameterValue parameter : parametersAction.getAllParameters()) {
                String name = parameter.getName();
                String value = parameter.createVariableResolver(build).resolve(name);
                if (value != null) {
                    buildParameters.put(name.toUpperCase(), value);
                }
            }
        }

        boolean hasError = false;

        // constructing JSON message
        JSONObject jsonObject = new JSONObject();
        String[] lines = parameters.split("\\r?\\n");
        if (lines.length > 0) {
            for (String line : lines) {
                String[] splitLine = line.split("=");
                if (splitLine.length == 2) {
                    String paramKey = splitLine[0];
                    String paramValue = splitLine[1];
                    if (StringUtils.isNotBlank(paramKey)) {
                        Matcher matcher = PARAM_PATTERN.matcher(paramValue);
                        if (matcher.find()) {
                            String param = matcher.group("param");
                            if (buildParameters.containsKey(param.toUpperCase())) {
                                paramValue = buildParameters.get(param);
                            }
                        }

                        LOGGER.info("\t- " + paramKey + "=" + paramValue);
                        jsonObject.put(Utils.toJava(paramKey), paramValue);
                    } else {
                        LOGGER.info("\t- Empty key for : {}", line);
                        console.println("Empty key for : " + line);
                        hasError = true;
                    }
                } else {
                    LOGGER.error("\t- Incorrect parameters format : {}", line);
                    console.println("Incorrect parameters format : " + line);
                    hasError = true;
                }
            }
        }

        if (hasError) {
            throw new IllegalStateException("Incorrect data");
        }

        return jsonObject.toString();
    }

    @Override
    public RabbitMqDescriptor getDescriptor() {
        return (RabbitMqDescriptor) super.getDescriptor();
    }

    @Extension
    public static class RabbitMqDescriptor extends BuildStepDescriptor<Builder> {

        private Configs configs;

        public RabbitMqDescriptor() {
            load();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Publish to Rabbit-MQ";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) {
            this.configs = Configs.fromJSON(json);

            save();

            return true;
        }

        public Configs getConfigs() {
            return configs;
        }

        public void setConfigs(Configs configs) {
            this.configs = configs;
        }

        private RabbitConfig getRabbitConfig(String configName) {
            return configs.getRabbitConfigs()
                          .stream()
                          .filter(rc -> rc.getName().equals(configName))
                          .findFirst()
                          .orElse(null);
        }

        public ListBoxModel doFillRabbitNameItems() {
            ListBoxModel options = new ListBoxModel();
            configs.rabbitConfigs.forEach(rc -> options.add(rc.name));
            return options;
        }

        public FormValidation doCheckParameters(@QueryParameter String parameters) {
            if (StringUtils.isBlank(parameters)) {
                return FormValidation.error("Parameters required");
            } else {
                String[] lines = parameters.split("\\r?\\n");
                for (String line : lines) {
                    String[] splitLine = line.split("=");
                    if (splitLine.length == 2) {
                        if (StringUtils.isBlank(splitLine[0])) {
                            return FormValidation.error("Empty key for : [%s]", line);
                        }
                    } else {
                        return FormValidation.error("Incorrect parameters format for value [%s]. Expected format is key=value", line);
                    }
                }

                return FormValidation.ok();
            }
        }
    }

    public static final class Configs extends AbstractDescribableImpl<Configs> {

        private final List<RabbitConfig> rabbitConfigs;

        @DataBoundConstructor
        public Configs(List<RabbitConfig> rabbitConfigs) {
            this.rabbitConfigs = rabbitConfigs != null ? new ArrayList<>(rabbitConfigs) : Collections.emptyList();
        }

        @Override
        public ConfigsDescriptor getDescriptor() {
            return (ConfigsDescriptor) super.getDescriptor();
        }

        public List<RabbitConfig> getRabbitConfigs() {
            return Collections.unmodifiableList(rabbitConfigs);
        }

        static Configs fromJSON(JSONObject jsonObject) {
            if (!jsonObject.containsKey("configs")) {
                return null;
            }

            List<RabbitConfig> rabbitConfigs = new ArrayList<>();

            JSONObject configsJSON = jsonObject.getJSONObject("configs");

            JSONObject rabbitConfigsJSON = configsJSON.optJSONObject("rabbitConfigs");
            if (rabbitConfigsJSON != null) {
                rabbitConfigs.add(RabbitConfig.fromJSON(rabbitConfigsJSON));
            }

            JSONArray rabbitConfigsJSONArray = configsJSON.optJSONArray("rabbitConfigs");
            if (rabbitConfigsJSONArray != null) {
                for (int i = 0; i < rabbitConfigsJSONArray.size(); i++) {
                    rabbitConfigs.add(RabbitConfig.fromJSON(rabbitConfigsJSONArray.getJSONObject(i)));
                }
            }

            return new Configs(rabbitConfigs);
        }

        @Extension
        public static class ConfigsDescriptor extends Descriptor<Configs> {
        }
    }

    public static class RabbitConfig extends AbstractDescribableImpl<RabbitConfig> {

        private String name;
        private String host;
        private int port;
        private String username;
        private String password;

        @DataBoundConstructor
        public RabbitConfig(String name, String host, int port, String username, String password) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
        }

        public String getName() {
            return name;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        static RabbitConfig fromJSON(JSONObject jsonObject) {
            String name = jsonObject.getString("name");
            String host = jsonObject.getString("host");
            int port = jsonObject.getInt("port");
            String username = jsonObject.getString("username");
            String password = jsonObject.getString("password");

            return new RabbitConfig(name, host, port, username, password);
        }

        @Override
        public RabbitConfigDescriptor getDescriptor() {
            return (RabbitConfigDescriptor) super.getDescriptor();
        }

        @Extension
        public static class RabbitConfigDescriptor extends Descriptor<RabbitConfig> {

            public FormValidation doCheckPort(@QueryParameter String value) {
                if (NumberUtils.isNumber(value)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error("Not a number");
                }
            }

            public FormValidation doTestConnection(@QueryParameter("host") final String host,
                                                   @QueryParameter("port") final String port,
                                                   @QueryParameter("username") final String username,
                                                   @QueryParameter("password") final String password) {
                try {
                    ConnectionFactory connectionFactory = new ConnectionFactory();
                    connectionFactory.setUsername(username);
                    connectionFactory.setPassword(password);
                    connectionFactory.setHost(host);
                    connectionFactory.setPort(Integer.parseInt(port));

                    Connection connection = connectionFactory.newConnection();
                    if (connection.isOpen()) {
                        connection.close();
                        return FormValidation.ok("Connection success");
                    } else {
                        return FormValidation.error("Connection failed");
                    }
                } catch (IOException | TimeoutException e) {
                    LOGGER.error("Connection error", e);
                    return FormValidation.error("Client error : " + e.getMessage());
                }
            }
        }
    }

}