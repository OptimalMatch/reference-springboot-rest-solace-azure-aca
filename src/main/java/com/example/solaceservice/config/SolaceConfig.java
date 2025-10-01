package com.example.solaceservice.config;

import com.solacesystems.jms.SolConnectionFactory;
import com.solacesystems.jms.SolJmsUtility;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;

import jakarta.jms.ConnectionFactory;

@Configuration
@EnableJms
@ConditionalOnProperty(name = "spring.jms.solace.enabled", havingValue = "true", matchIfMissing = false)
public class SolaceConfig {

    @Value("${spring.jms.solace.host}")
    private String host;

    @Value("${spring.jms.solace.username}")
    private String username;

    @Value("${spring.jms.solace.password}")
    private String password;

    @Value("${spring.jms.solace.vpn-name}")
    private String vpnName;

    @Bean
    public ConnectionFactory connectionFactory() throws Exception {
        SolConnectionFactory solConnectionFactory = SolJmsUtility.createConnectionFactory();
        solConnectionFactory.setHost(host);
        solConnectionFactory.setUsername(username);
        solConnectionFactory.setPassword(password);
        solConnectionFactory.setVPN(vpnName);

        // Return the connection factory without explicit casting
        // The Solace connection factory should implement Jakarta JMS interfaces
        return solConnectionFactory;
    }

    @Bean
    public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory) {
        JmsTemplate jmsTemplate = new JmsTemplate(connectionFactory);
        jmsTemplate.setPubSubDomain(false); // Use queues, not topics
        jmsTemplate.setDeliveryPersistent(true);
        return jmsTemplate;
    }

    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(ConnectionFactory connectionFactory) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setPubSubDomain(false); // Use queues, not topics
        factory.setSessionTransacted(true);
        return factory;
    }
}