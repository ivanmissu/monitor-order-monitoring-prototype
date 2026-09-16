package com.monitor.server.integration;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.util.HashMap;
import java.util.Map;

/**
 * integration profile 的 Kafka 装配：进程内启动一个真实 KRaft broker
 * （{@link EmbeddedKafkaKraftBroker}，即 Kafka 官方 {@code KafkaClusterTestKit}），
 * 并以与生产 {@code application.yml} 完全一致的语义
 * （批量监听、手动位点提交、latest 起播、500 条攒批）装配生产者与消费者工厂。
 *
 * <p>与 demo profile 的区别：这里 {@code EventConsumer}、KafkaTemplate 都走真实网络栈与
 * 真实 broker 存储，可用 kafka-clients 自身的行为验证消费、位点与重平衡。
 *
 * <p>生产部署（profile 未激活本类）时，bootstrap 来自 {@code spring.kafka.bootstrap-servers}
 * 指向外部集群，互不影响。
 */
@Configuration
@EnableKafka
@Profile("integration")
public class IntegrationConfig {

    private static final Logger log = LoggerFactory.getLogger(IntegrationConfig.class);

    /** 与 application.yml 主段一致的事件主题。 */
    public static final String TOPIC = "${monitor.ingest.topic:biz.order.event}";

    /**
     * 进程内 KRaft broker。随机端口，启动完成前阻塞（afterPropertiesSet），
     * 因此所有依赖它的工厂 Bean 都能直接拿到可用地址。
     */
    @Bean(destroyMethod = "destroy")
    public EmbeddedKafkaBroker embeddedKafkaBroker() {
        EmbeddedKafkaKraftBroker broker = new EmbeddedKafkaKraftBroker(1, 1, "biz.order.event");
        broker.brokerProperty("log.dirs", System.getProperty("java.io.tmpdir")
                + "/monitor-embedded-kafka-" + ProcessHandle.current().pid());
        broker.setAdminTimeout(30);
        log.info("启动内嵌 KRaft Kafka broker（KafkaClusterTestKit，随机端口，topic=biz.order.event）...");
        return broker;
    }

    @Bean
    public ProducerFactory<String, String> producerFactory(EmbeddedKafkaBroker broker) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.ACKS_CONFIG, "1");
        cfg.put(ProducerConfig.LINGER_MS_CONFIG, 50);
        return new DefaultKafkaProducerFactory<>(cfg);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }

    /**
     * 批量监听容器工厂：与生产配置一致 ——
     * 手动位点（ack-mode: manual_immediate）、latest 起播、单批最多 500 条。
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            EmbeddedKafkaBroker broker) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, "monitor-consumer");
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        ConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(cfg);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.setBatchListener(true);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        log.info("Kafka 监听容器已指向内嵌 broker: {}", broker.getBrokersAsString());
        return factory;
    }
}
