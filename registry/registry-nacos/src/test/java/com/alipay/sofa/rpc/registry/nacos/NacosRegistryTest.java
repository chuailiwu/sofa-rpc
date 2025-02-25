/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.rpc.registry.nacos;

import com.alipay.sofa.rpc.client.ProviderGroup;
import com.alipay.sofa.rpc.client.ProviderInfo;
import com.alipay.sofa.rpc.config.ApplicationConfig;
import com.alipay.sofa.rpc.config.ConsumerConfig;
import com.alipay.sofa.rpc.config.ProviderConfig;
import com.alipay.sofa.rpc.config.RegistryConfig;
import com.alipay.sofa.rpc.config.ServerConfig;
import com.alipay.sofa.rpc.listener.ProviderInfoListener;
import com.alipay.sofa.rpc.registry.RegistryFactory;
import com.alipay.sofa.rpc.registry.nacos.base.BaseNacosTest;
import com.alipay.sofa.rpc.server.ServerFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The type Nacos registry test.
 *
 * @author <a href=mailto:jervyshi@gmail.com>JervyShi</a>
 */
public class NacosRegistryTest extends BaseNacosTest {

    private static RegistryConfig registryConfig;

    private NacosRegistry         registry;

    private ServerConfig          serverConfig;

    /**
     * Sets up.
     */
    @Before
    public void setUp() {
        registryConfig = new RegistryConfig()
            .setProtocol("nacos")
            .setSubscribe(true)
            .setAddress("127.0.0.1:" + nacosProcess.getServerPort())
            .setRegister(true);

        registry = (NacosRegistry) RegistryFactory.getRegistry(registryConfig);
        registry.init();
        Assert.assertTrue(registry.start());
    }

    /**
     * Tear down.
     */
    @After
    public void tearDown() {
        registry.destroy();
        registry = null;
        serverConfig.destroy();
    }

    /**
     * 测试Zookeeper Provider Observer
     *
     * @throws Exception the exception
     */
    @Test
    public void testProviderObserver() throws Exception {
        int timeoutPerSub = 2000;

        //wait nacos startup ok
        TimeUnit.SECONDS.sleep(10);

        serverConfig = new ServerConfig()
            .setProtocol("bolt")
            .setHost("0.0.0.0")
            .setPort(12200);

        ProviderConfig<?> provider = new ProviderConfig();
        provider.setInterfaceId("com.alipay.xxx.NacosTestService")
            .setApplication(new ApplicationConfig().setAppName("test-server"))
            .setUniqueId("nacos-test")
            .setProxy("javassist")
            .setRegister(true)
            .setRegistry(registryConfig)
            .setSerialization("hessian2")
            .setServer(serverConfig)
            .setWeight(222)
            .setTimeout(3000);

        // 注册
        try {
            registry.register(provider);
        } catch (Throwable e) {
            LOGGER.error("register provider fail", e);
            Assert.fail("register provider fail");
        }
        Thread.sleep(1000);

        ConsumerConfig<?> consumer = new ConsumerConfig();
        consumer.setInterfaceId("com.alipay.xxx.NacosTestService")
            .setApplication(new ApplicationConfig().setAppName("test-server"))
            .setUniqueId("nacos-test")
            .setProxy("javassist")
            .setSubscribe(true)
            .setSerialization("java")
            .setInvokeType("sync")
            .setTimeout(4444);

        // 订阅
        CountDownLatch latch = new CountDownLatch(1);
        MockProviderInfoListener providerInfoListener = new MockProviderInfoListener();
        providerInfoListener.setCountDownLatch(latch);
        consumer.setProviderInfoListener(providerInfoListener);
        List<ProviderGroup> all = registry.subscribe(consumer);
        providerInfoListener.updateAllProviders(all);

        Map<String, ProviderInfo> ps = providerInfoListener.getData();
        Assert.assertEquals("after register: 1", 1, ps.size());

        LOGGER.info("after register success {}", ps);

        // 订阅 错误的uniqueId
        ConsumerConfig<?> consumerNoUniqueId = new ConsumerConfig();
        consumerNoUniqueId.setInterfaceId("com.alipay.xxx.NacosTestService")
            .setApplication(new ApplicationConfig().setAppName("test-server"))
            .setProxy("javassist")
            .setSubscribe(true)
            .setSerialization("java")
            .setInvokeType("sync")
            .setTimeout(4444);
        latch = new CountDownLatch(1);
        MockProviderInfoListener wrongProviderInfoListener = new MockProviderInfoListener();
        wrongProviderInfoListener.setCountDownLatch(latch);
        consumerNoUniqueId.setProviderInfoListener(wrongProviderInfoListener);
        all = registry.subscribe(consumerNoUniqueId);
        wrongProviderInfoListener.updateAllProviders(all);
        ps = wrongProviderInfoListener.getData();
        Assert.assertEquals("wrong uniqueId: 0", 0, ps.size());

        LOGGER.info("wrong uniqueId {}", ps);

        // 反注册
        latch = new CountDownLatch(1);
        providerInfoListener.setCountDownLatch(latch);
        registry.unRegister(provider);
        latch.await(timeoutPerSub, TimeUnit.MILLISECONDS);
        Assert.assertEquals("after unregister: 0", 0, ps.size());

        LOGGER.info("after unregister {}", ps);

        // 一次发2个端口的再次注册
        latch = new CountDownLatch(2);
        providerInfoListener.setCountDownLatch(latch);
        provider.getServer().add(new ServerConfig()
            .setProtocol("bolt")
            .setHost("0.0.0.0")
            .setPort(12201));
        registry.register(provider);

        latch.await(timeoutPerSub * 2, TimeUnit.MILLISECONDS);
        ps = providerInfoListener.getData();
        //nacos has bug now
        Assert.assertEquals("after register two servers: 1", 1, ps.size());

        LOGGER.info("after register two servers {}", ps);

        // 重复订阅
        ConsumerConfig<?> consumer2 = new ConsumerConfig();
        consumer2.setInterfaceId("com.alipay.xxx.NacosTestService")
            .setUniqueId("nacos-test")
            .setApplication(new ApplicationConfig().setAppName("test-server"))
            .setProxy("javassist")
            .setSubscribe(true)
            .setSerialization("java")
            .setInvokeType("sync")
            .setTimeout(4444);
        CountDownLatch latch2 = new CountDownLatch(1);
        MockProviderInfoListener providerInfoListener2 = new MockProviderInfoListener();
        providerInfoListener2.setCountDownLatch(latch2);
        consumer2.setProviderInfoListener(providerInfoListener2);
        providerInfoListener2.updateAllProviders(registry.subscribe(consumer2));
        latch2.await(timeoutPerSub, TimeUnit.MILLISECONDS);

        Map<String, ProviderInfo> ps2 = providerInfoListener2.getData();
        Assert.assertEquals("after register duplicate: 1", 1, ps2.size());

        LOGGER.info("after register duplicate {}", ps);

        // 取消订阅者1
        registry.unSubscribe(consumer);

        // 批量反注册，判断订阅者2的数据
        latch = new CountDownLatch(2);
        providerInfoListener2.setCountDownLatch(latch);
        List<ProviderConfig> providerConfigList = new ArrayList<ProviderConfig>();
        providerConfigList.add(provider);
        registry.batchUnRegister(providerConfigList);

        latch.await(timeoutPerSub * 2, TimeUnit.MILLISECONDS);
        Assert.assertEquals("after unregister: 1", 1, ps2.size());

        LOGGER.info("after unregister consumer, and consumer2 {}", ps);

        // 批量取消订阅
        List<ConsumerConfig> consumerConfigList = new ArrayList<ConsumerConfig>();
        consumerConfigList.add(consumer2);
        registry.batchUnSubscribe(consumerConfigList);
    }

    /**
     * 测试NacosRegistry 对 serverconfig中 VirtualHost 和 VirtualPort 参数的支持情况
     *
     * @throws Exception the exception
     */
    @Test
    public void testVirtualHostAndVirtualPort() throws Exception {
        //wait nacos startup ok
        TimeUnit.SECONDS.sleep(10);
        // 模拟的场景 client -> proxy:127.7.7.7:8888 -> netty:0.0.0.0:12200
        String virtualHost = "127.7.7.7";
        int virtualPort = 8888;
        serverConfig = new ServerConfig()
            .setProtocol("bolt")
            .setHost("0.0.0.0")
            .setPort(12200)
            .setAdaptivePort(false) // Turn off adaptive port
            .setVirtualHost(virtualHost)
            .setVirtualPort(virtualPort);

        // Verify the influence of virtualHost and virtualPort on the parameters when creating rpcserver
        Method m_resolveServerConfig = ServerFactory.class.getDeclaredMethod("resolveServerConfig", ServerConfig.class);
        m_resolveServerConfig.setAccessible(true);
        m_resolveServerConfig.invoke(new ServerFactory(), serverConfig);
        Assert
            .assertNotEquals("boundhost should not be equal to virtualHost", serverConfig.getBoundHost(), virtualHost);
        Assert.assertEquals("boundPort should be oriPort", serverConfig.getPort(), 12200);

        ProviderConfig<?> provider = new ProviderConfig();
        provider.setInterfaceId("com.alipay.xxx.NacosTestService2")
            .setApplication(new ApplicationConfig().setAppName("test-server2"))
            .setUniqueId("nacos-test2")
            .setProxy("javassist")
            .setRegister(true)
            .setRegistry(registryConfig)
            .setServer(serverConfig);

        // 注册
        registry.register(provider);
        Thread.sleep(1000);

        ConsumerConfig<?> consumer = new ConsumerConfig();
        consumer.setInterfaceId("com.alipay.xxx.NacosTestService2")
            .setApplication(new ApplicationConfig().setAppName("test-consumer2"))
            .setUniqueId("nacos-test2")
            .setProxy("javassist")
            .setSubscribe(true)
            .setSerialization("java")
            .setInvokeType("sync")
            .setTimeout(4444);

        // 订阅
        CountDownLatch latch = new CountDownLatch(1);
        MockProviderInfoListener providerInfoListener = new MockProviderInfoListener();
        providerInfoListener.setCountDownLatch(latch);
        consumer.setProviderInfoListener(providerInfoListener);
        List<ProviderGroup> all = registry.subscribe(consumer);
        //        providerInfoListener.updateAllProviders(all);
        latch.await(2000, TimeUnit.MILLISECONDS);

        Map<String, ProviderInfo> ps = providerInfoListener.getData();
        Assert.assertEquals("after register: 1", 1, ps.size());
        Map.Entry<String, ProviderInfo> psEntry = (Map.Entry) ps.entrySet().toArray()[0];
        ProviderInfo pri = psEntry.getValue();
        Assert.assertEquals("The provider's key should consist of virtualHost and virtualPort", psEntry.getKey(),
            virtualHost + ":" + virtualPort);
        Assert.assertEquals("The provider's host should be virtualHost", virtualHost, pri.getHost());
        Assert.assertEquals("The provider's port should be virtualPort", virtualPort, pri.getPort());
    }

    private static class MockProviderInfoListener implements ProviderInfoListener {

        /**
         * The Ps.
         */
        ConcurrentMap<String, ProviderInfo> ps = new ConcurrentHashMap<String, ProviderInfo>();

        private CountDownLatch              countDownLatch;

        /**
         * Sets count down latch.
         *
         * @param countDownLatch the count down latch
         */
        public void setCountDownLatch(CountDownLatch countDownLatch) {
            this.countDownLatch = countDownLatch;
        }

        @Override
        public void addProvider(ProviderGroup providerGroup) {
            for (ProviderInfo providerInfo : providerGroup.getProviderInfos()) {
                ps.put(providerInfo.getHost() + ":" + providerInfo.getPort(), providerInfo);
            }
            if (countDownLatch != null) {
                countDownLatch.countDown();
            }
        }

        @Override
        public void removeProvider(ProviderGroup providerGroup) {
            for (ProviderInfo providerInfo : providerGroup.getProviderInfos()) {
                ps.remove(providerInfo.getHost() + ":" + providerInfo.getPort());
            }
            if (countDownLatch != null) {
                countDownLatch.countDown();
            }
        }

        @Override
        public void updateProviders(ProviderGroup providerGroup) {
            ps.clear();
            for (ProviderInfo providerInfo : providerGroup.getProviderInfos()) {
                ps.put(providerInfo.getHost() + ":" + providerInfo.getPort(), providerInfo);
            }
            if (countDownLatch != null) {
                countDownLatch.countDown();
            }
        }

        @Override
        public void updateAllProviders(List<ProviderGroup> providerGroups) {
            ps.clear();
            for (ProviderGroup providerGroup : providerGroups) {
                for (ProviderInfo providerInfo : providerGroup.getProviderInfos()) {
                    ps.put(providerInfo.getHost() + ":" + providerInfo.getPort(), providerInfo);
                }
            }
            if (countDownLatch != null) {
                countDownLatch.countDown();
            }
        }

        /**
         * Gets data.
         *
         * @return the data
         */
        public Map<String, ProviderInfo> getData() {
            return ps;
        }
    }

}