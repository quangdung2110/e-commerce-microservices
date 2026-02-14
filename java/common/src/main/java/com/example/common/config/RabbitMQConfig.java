package com.example.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ Configuration for Command/Task Queue
 * Used by Temporal to send commands to services (Inventory, Payment, Shipping)
 * 
 * Architecture:
 * - Temporal Workflow → RabbitMQ Queue → Service Worker
 * - Services publish results back via Kafka Events
 */
@Configuration
@EnableConfigurationProperties(RabbitMQProperties.class)
@ConditionalOnProperty(prefix = "rabbitmq", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RabbitMQConfig {
    
    private final RabbitMQProperties properties;

    @Bean
    public ConnectionFactory connectionFactory() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory();
        connectionFactory.setHost(properties.getHost());
        connectionFactory.setPort(properties.getPort());
        connectionFactory.setUsername(properties.getUsername());
        connectionFactory.setPassword(properties.getPassword());
        connectionFactory.setVirtualHost(properties.getVirtualHost());
        
        // Enable publisher confirms for reliability
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        
        return connectionFactory;
    }

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        converter.setCreateMessageIds(true);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, 
                                        MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        
        // Enable mandatory flag to handle unroutable messages
        template.setMandatory(true);
        template.setReturnsCallback(returned -> {
            // Log undeliverable messages
            System.err.println("Message returned: " + returned.getMessage() + 
                             ", replyCode: " + returned.getReplyCode() + 
                             ", replyText: " + returned.getReplyText());
        });
        
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setConcurrentConsumers(3);
        factory.setMaxConcurrentConsumers(10);
        factory.setPrefetchCount(10);
        
        // Enable manual acknowledgment for better control
        factory.setDefaultRequeueRejected(false);
        
        return factory;
    }

    @Bean
    public AmqpTemplate amqpTemplate(RabbitTemplate rabbitTemplate) {
        return rabbitTemplate;
    }
}
