/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.test.clustering.cluster.ejb3;

import javax.ejb.NoSuchEJBException;
import javax.inject.Inject;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.transaction.UserTransaction;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.junit.InSequence;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.test.clustering.ClusteringTestConstants;
import org.jboss.as.test.clustering.cluster.ClusterAbstractTestCase;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests that the stateful timeout annotation works
 *
 * @author Stuart Douglas
 */
@RunWith(Arquillian.class)
public class StatefulTimeoutTestCase extends ClusterAbstractTestCase {

    private static final String ARCHIVE_NAME = "StatefulTimeoutTestCase";

    @Inject
    private UserTransaction userTransaction;

    @Deployment(name = DEPLOYMENT_1, managed = false)
    @TargetsContainer(CONTAINER_1)
    public static Archive<?> deployment0() {
        return createJar();
    }

    @Deployment(name = DEPLOYMENT_2, managed = false)
    @TargetsContainer(CONTAINER_2)
    public static Archive<?> deployment1() {
        return createJar();
    }

    private static Archive<?> createJar() {
        JavaArchive jar = ShrinkWrap.create(JavaArchive.class, ARCHIVE_NAME + ".jar");
        jar.addPackage(StatefulTimeoutTestCase.class.getPackage());
        jar.addClasses(ClusterAbstractTestCase.class, ClusteringTestConstants.class);
        jar.add(EmptyAsset.INSTANCE, "META-INF/beans.xml");
        return jar;
    }

    protected <T> T lookup(InitialContext iniCtx, Class<T> beanType) throws NamingException {
        return beanType.cast(iniCtx.lookup("java:global/" + ARCHIVE_NAME + "/" + beanType.getSimpleName() + "!" + beanType.getName()));
    }

    // TODO: Workaround for https://issues.jboss.org/browse/ARQ-351, remove after fixed.

    @Override
    public void beforeTestMethod() {
        // Do nothing.
    }

    @Override
    public void afterTestMethod() {
        // Do nothing.
    }

    @Test
    @InSequence(Integer.MIN_VALUE)
    @RunAsClient
    public void setup() {
        start(CONTAINERS);
        deploy(DEPLOYMENTS);
    }

    @Test
    @InSequence(Integer.MAX_VALUE)
    @RunAsClient
    public void cleanup() {
        start(CONTAINERS);
        undeploy(DEPLOYMENTS);
    }

    @Test
    @OperateOnDeployment(DEPLOYMENT_1)
    public void testStatefulTimeout(@ArquillianResource InitialContext iniCtx) throws Exception {
        ClusteredCacheBean.preDestroy = false;
        ClusteredCacheBean.prePassivate = false;
        ClusteredCacheBean sfsb1 = lookup(iniCtx, ClusteredCacheBean.class);
        Assert.assertFalse(ClusteredCacheBean.preDestroy);
        Assert.assertFalse(ClusteredCacheBean.prePassivate);
        sfsb1.increment();
        Assert.assertTrue(ClusteredCacheBean.prePassivate);
        Thread.sleep(1500);
        NoSuchEJBException exception = null;
        try {
            sfsb1.increment();
        } catch (NoSuchEJBException e) {
            exception = e;
        }
        Assert.assertNotNull(exception);
        Assert.assertTrue(ClusteredCacheBean.preDestroy);
    }

    @Test
    @OperateOnDeployment(DEPLOYMENT_1)
    public void testClusteredStatefulTimeout(@ArquillianResource InitialContext iniCtx) throws Exception {
        ClusteredBean.preDestroy = false;
        ClusteredBean.prePassivate = false;
        ClusteredBean sfsb1 = lookup(iniCtx, ClusteredBean.class);
        Assert.assertFalse(ClusteredBean.preDestroy);
        Assert.assertFalse(ClusteredBean.prePassivate);
        sfsb1.increment();
        Assert.assertTrue(ClusteredBean.prePassivate);
        Thread.sleep(1500);
        NoSuchEJBException exception = null;
        try {
            sfsb1.increment();
        } catch (NoSuchEJBException e) {
            exception = e;
        }
        Assert.assertNotNull(exception);
        Assert.assertTrue(ClusteredBean.preDestroy);
    }

    @Test
    @OperateOnDeployment(DEPLOYMENT_1)
    public void testStatefulBeanNotDiscardedWhileInTransaction(@ArquillianResource InitialContext iniCtx) throws Exception {
        ClusteredBean.preDestroy = false;
        ClusteredBean.prePassivate = false;
        try {
            userTransaction.begin();
            ClusteredBean sfsb1 = lookup(iniCtx, ClusteredBean.class);
            Assert.assertFalse(ClusteredBean.preDestroy);
            Assert.assertFalse(ClusteredBean.prePassivate);
            sfsb1.increment();
            Assert.assertFalse(ClusteredBean.prePassivate);
            Thread.sleep(1500);
            sfsb1.increment();
            Assert.assertFalse(ClusteredBean.preDestroy);
//            Assert.assertFalse(ClusteredBean.prePassivate);
            userTransaction.commit();
            Assert.assertTrue(ClusteredBean.prePassivate);
        } catch (Exception e) {
            e.printStackTrace();
            userTransaction.rollback();
        }
    }

    @Test
    @OperateOnDeployment(DEPLOYMENT_1)
    public void testNested(@ArquillianResource InitialContext iniCtx) throws Exception {

        NestedBean.preDestroy = false;
        NestedBean sfsb1 = lookup(iniCtx, NestedBean.class);
        Assert.assertFalse(NestedBean.preDestroy);
        sfsb1.increment();
        Thread.sleep(1500);
        NoSuchEJBException exception = null;
        try {
            sfsb1.increment();
        } catch (NoSuchEJBException e) {
            exception = e;
        }
        Assert.assertNotNull(exception);
        Assert.assertTrue(NestedBean.preDestroy);
    }
}
