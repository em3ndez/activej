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

package io.activej.http;

import io.activej.common.Checks;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.promise.Promise;
import io.activej.promise.jmx.PromiseStats;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import io.activej.reactor.jmx.ReactiveJmxBeanWithStats;

import java.time.Duration;

import static io.activej.reactor.Reactive.checkInReactorThread;

public abstract class ServletWithStats extends AbstractReactive
	implements AsyncServlet, ReactiveJmxBeanWithStats {
	private static final boolean CHECKS = Checks.isEnabled(ServletWithStats.class);

	private final PromiseStats stats = PromiseStats.create(Duration.ofMinutes(5));

	protected ServletWithStats(Reactor reactor) {
		super(reactor);
	}

	protected abstract Promise<HttpResponse> doServe(HttpRequest request);

	@Override
	public final Promise<HttpResponse> serve(HttpRequest request) {
		if (CHECKS) checkInReactorThread(this);
		return doServe(request)
			.whenComplete(stats.recordStats());
	}

	@JmxAttribute
	public PromiseStats getStats() {
		return stats;
	}

	public void setStatsHistogramLevels(long[] levels) {
		stats.setHistogram(levels);
	}

}
