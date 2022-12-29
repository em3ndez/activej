/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.launchers.fs;

import io.activej.async.service.ReactiveTaskScheduler;
import io.activej.common.exception.MalformedDataException;
import io.activej.config.Config;
import io.activej.config.ConfigModule;
import io.activej.eventloop.Eventloop;
import io.activej.fs.ActiveFs;
import io.activej.fs.cluster.ClusterActiveFs;
import io.activej.fs.cluster.DiscoveryService;
import io.activej.fs.cluster.FsPartitions;
import io.activej.http.AsyncHttpServer;
import io.activej.http.AsyncServlet;
import io.activej.inject.annotation.Eager;
import io.activej.inject.annotation.Named;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.Module;
import io.activej.inject.module.ModuleBuilder;
import io.activej.jmx.JmxModule;
import io.activej.launcher.Launcher;
import io.activej.launchers.fs.gui.ActiveFsGuiServlet;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.service.ServiceGraphModule;

import static io.activej.inject.module.Modules.combine;
import static io.activej.launchers.fs.Initializers.ofClusterActiveFs;
import static io.activej.launchers.initializers.Initializers.ofHttpServer;
import static io.activej.launchers.initializers.Initializers.ofReactorTaskScheduler;

public class ClusterTcpClientLauncher extends Launcher {
	public static final String PROPERTIES_FILE = "activefs-client.properties";

	public static final String DEFAULT_DEAD_CHECK_INTERVAL = "1 seconds";
	public static final String DEFAULT_SERVER_LISTEN_ADDRESS = "*:9000";
	public static final String DEFAULT_GUI_SERVER_LISTEN_ADDRESS = "*:8080";

	@Provides
	NioReactor reactor() {
		return Eventloop.create();
	}

	//[START EXAMPLE]
	@Provides
	@Eager
	@Named("clusterDeadCheck")
	ReactiveTaskScheduler deadCheckScheduler(Config config, FsPartitions partitions) {
		return ReactiveTaskScheduler.create(partitions.getReactor(), partitions::checkDeadPartitions)
				.withInitializer(ofReactorTaskScheduler(config.getChild("activefs.repartition.deadCheck")));
	}

	@Provides
	@Eager
	AsyncHttpServer guiServer(NioReactor reactor, AsyncServlet servlet, Config config) {
		return AsyncHttpServer.create(reactor, servlet)
				.withInitializer(ofHttpServer(config.getChild("activefs.http.gui")));
	}

	@Provides
	AsyncServlet guiServlet(ActiveFs activeFs) {
		return ActiveFsGuiServlet.create(activeFs, "Cluster FS Client");
	}

	@Provides
	ActiveFs remoteActiveFs(Reactor reactor, FsPartitions partitions, Config config) {
		return ClusterActiveFs.create(reactor, partitions)
				.withInitializer(ofClusterActiveFs(config.getChild("activefs.cluster")));
	}

	@Provides
	DiscoveryService discoveryService(NioReactor reactor, Config config) throws MalformedDataException {
		return Initializers.constantDiscoveryService(reactor, config.getChild("activefs.cluster"));
	}

	@Provides
	FsPartitions fsPartitions(Reactor reactor, DiscoveryService discoveryService) {
		return FsPartitions.create(reactor, discoveryService);
	}
	//[END EXAMPLE]

	@Provides
	Config config() {
		return createConfig()
				.overrideWith(Config.ofClassPathProperties(PROPERTIES_FILE, true))
				.overrideWith(Config.ofSystemProperties("config"));
	}

	protected Config createConfig() {
		return Config.create()
				.with("activefs.listenAddresses", DEFAULT_SERVER_LISTEN_ADDRESS)
				.with("activefs.http.gui.listenAddresses", DEFAULT_GUI_SERVER_LISTEN_ADDRESS)
				.with("activefs.repartition.deadCheck.schedule.type", "interval")
				.with("activefs.repartition.deadCheck.schedule.value", DEFAULT_DEAD_CHECK_INTERVAL);
	}

	@Override
	protected final Module getModule() {
		return combine(
				ModuleBuilder.create()
						.bind(Reactor.class).to(NioReactor.class)
						.build(),
				ServiceGraphModule.create(),
				JmxModule.create(),
				ConfigModule.create()
						.withEffectiveConfigLogger());
	}

	@Override
	protected void run() throws Exception {
		awaitShutdown();
	}

	public static void main(String[] args) throws Exception {
		new ClusterTcpClientLauncher().launch(args);
	}
}
