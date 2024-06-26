package io.activej.launchers.http;

import io.activej.async.service.TaskScheduler;
import io.activej.dns.DnsClient;
import io.activej.dns.IDnsClient;
import io.activej.eventloop.Eventloop;
import io.activej.http.*;
import io.activej.inject.Injector;
import io.activej.inject.Key;
import io.activej.inject.annotation.Eager;
import io.activej.inject.annotation.Named;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.AbstractModule;
import io.activej.jmx.JmxModule;
import io.activej.launcher.LauncherService;
import io.activej.launchers.initializers.Initializers;
import io.activej.net.PrimaryServer;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.worker.WorkerPool;
import io.activej.worker.WorkerPoolModule;
import io.activej.worker.WorkerPools;
import io.activej.worker.annotation.Worker;
import org.junit.Test;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;

public class ObjectNameRenameTest {

	@Test
	public void testRenaming() throws ExecutionException, InterruptedException, MalformedObjectNameException {
		Injector injector = Injector.of(
			new TestModule(),
			JmxModule.builder()
				.initialize(Initializers.renamedClassNames(
					Map.of(
						HttpServer.class, "AsyncHttpServer",
						IHttpClient.class, "AsyncHttpClient",
						TaskScheduler.class, "EventloopTaskScheduler",
						Reactor.class, Eventloop.class.getName(),
						NioReactor.class, Eventloop.class.getName()
					)))
				.build(),
			WorkerPoolModule.create()
		);

		injector.createEagerInstances();
		for (LauncherService service : injector.getInstance(new Key<Set<LauncherService>>() {})) {
			service.start().get();
		}

		MBeanServer beanServer = ManagementFactory.getPlatformMBeanServer();

		assertEquals(1, beanServer.queryNames(new ObjectName("io.activej.eventloop:type=Eventloop"), null).size());
		assertEquals(4, beanServer.queryNames(new ObjectName("io.activej.eventloop:type=Eventloop,scope=Worker,workerId=worker-*"), null).size());

		assertEquals(1, beanServer.queryNames(new ObjectName("io.activej.async.service:type=EventloopTaskScheduler"), null).size());

		assertEquals(1, beanServer.queryNames(new ObjectName("io.activej.http:type=AsyncHttpClient,qualifier=Test"), null).size());
		assertEquals(4, beanServer.queryNames(new ObjectName("io.activej.http:type=AsyncHttpServer,scope=Worker,workerId=worker-*"), null).size());
	}

	private static class TestModule extends AbstractModule {
		@Provides
		WorkerPool workerPool(WorkerPools pools) {
			return pools.createPool(4);
		}

		@Provides
		NioReactor primaryReactor() {
			return Eventloop.create();
		}

		@Provides
		@Worker
		NioReactor workerReactor() {
			return Eventloop.create();
		}

		@Provides
		@Eager
		PrimaryServer primaryServer(NioReactor primaryReactor, WorkerPool.Instances<HttpServer> workerServers) {
			return PrimaryServer.builder(primaryReactor, workerServers).build();
		}

		@Provides
		@Worker
		HttpServer workerServer(NioReactor workerReactor) {
			return HttpServer.builder(workerReactor, request -> HttpResponse.ok200().toPromise()).build();
		}

		@Provides
		IDnsClient dnsClient(NioReactor reactor){
			return DnsClient.create(reactor, HttpUtils.inetAddress("8.8.8.8"));
		}

		@Provides
		@Named("Test")
		@Eager
		IHttpClient httpClient(NioReactor reactor, IDnsClient dnsClient) {
			return HttpClient.create(reactor, dnsClient);
		}

		@Provides
		@Eager
		TaskScheduler scheduler(NioReactor reactor) {
			return TaskScheduler.builder(reactor, Promise::complete)
				.withSchedule(TaskScheduler.Schedule.immediate())
				.build();
		}
	}
}
