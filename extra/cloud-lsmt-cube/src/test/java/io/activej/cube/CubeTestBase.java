package io.activej.cube;

import io.activej.async.function.AsyncSupplier;
import io.activej.codegen.DefiningClassLoader;
import io.activej.common.ref.RefLong;
import io.activej.cube.linear.CubeUplinkMySql;
import io.activej.cube.linear.MeasuresValidator;
import io.activej.cube.linear.PrimaryKeyCodecs;
import io.activej.cube.ot.CubeDiff;
import io.activej.cube.ot.CubeDiffCodec;
import io.activej.cube.ot.CubeDiffScheme;
import io.activej.cube.ot.CubeOT;
import io.activej.etl.LogDiff;
import io.activej.etl.LogDiffCodec;
import io.activej.etl.LogOT;
import io.activej.ot.OTCommit;
import io.activej.ot.repository.AsyncOTRepository;
import io.activej.ot.repository.OTRepositoryMySql;
import io.activej.ot.system.OTSystem;
import io.activej.ot.uplink.AsyncOTUplink;
import io.activej.ot.uplink.ReactiveOTUplink;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.ClassBuilderConstantsRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static io.activej.cube.TestUtils.*;
import static io.activej.test.TestUtils.dataSource;

@RunWith(Parameterized.class)
public abstract class CubeTestBase {
	public static final OTSystem<LogDiff<CubeDiff>> LOG_OT = LogOT.createLogOT(CubeOT.createCubeOT());
	public static final CubeDiffScheme<LogDiff<CubeDiff>> DIFF_SCHEME = CubeDiffScheme.ofLogDiffs();
	public static final DefiningClassLoader CLASS_LOADER = DefiningClassLoader.create();

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ClassBuilderConstantsRule classBuilderConstantsRule = new ClassBuilderConstantsRule();

	@Parameter()
	public String testName;

	@Parameter(1)
	public UplinkFactory<AsyncOTUplink<Long, LogDiff<CubeDiff>, ?>> uplinkFactory;

	public static final Executor EXECUTOR = Executors.newCachedThreadPool();
	public static final DataSource DATA_SOURCE;

	public NioReactor reactor;

	@Before
	public void setUp() throws Exception {
		reactor = Reactor.getCurrentReactor();
	}

	static {
		try {
			DATA_SOURCE = dataSource("test.properties");
		} catch (IOException | SQLException e) {
			throw new AssertionError(e);
		}
	}

	@Parameters(name = "{0}")
	public static Collection<Object[]> getParameters() {
		return List.of(
				new Object[]{
						"OT graph",
						new UplinkFactory<ReactiveOTUplink<Long, LogDiff<CubeDiff>, OTCommit<Long, LogDiff<CubeDiff>>>>() {
							@Override
							public ReactiveOTUplink<Long, LogDiff<CubeDiff>, OTCommit<Long, LogDiff<CubeDiff>>> createUninitialized(ReactiveCube cube) {
								Reactor reactor = Reactor.getCurrentReactor();
								AsyncOTRepository<Long, LogDiff<CubeDiff>> repository = OTRepositoryMySql.create(reactor, EXECUTOR, DATA_SOURCE, AsyncSupplier.of(new RefLong(0)::inc),
										LOG_OT, LogDiffCodec.create(CubeDiffCodec.create(cube)));
								return ReactiveOTUplink.create(repository, LOG_OT);
							}

							@Override
							public void initialize(ReactiveOTUplink<Long, LogDiff<CubeDiff>, OTCommit<Long, LogDiff<CubeDiff>>> uplink) {
								noFail(() -> initializeRepository((OTRepositoryMySql<LogDiff<CubeDiff>>) uplink.getRepository()));
							}
						}},

				// Linear
				new Object[]{
						"Linear graph",
						new UplinkFactory<CubeUplinkMySql>() {
							@Override
							public CubeUplinkMySql createUninitialized(ReactiveCube cube) {
								return CubeUplinkMySql.create(EXECUTOR, DATA_SOURCE, PrimaryKeyCodecs.ofCube(cube))
										.withMeasuresValidator(MeasuresValidator.ofCube(cube));
							}

							@Override
							public void initialize(CubeUplinkMySql uplink) {
								noFail(() -> initializeUplink(uplink));
							}
						}
				}
		);
	}

	protected interface UplinkFactory<U extends AsyncOTUplink<Long, LogDiff<CubeDiff>, ?>> {
		default U create(ReactiveCube cube) {
			U uplink = createUninitialized(cube);
			initialize(uplink);
			return uplink;
		}

		U createUninitialized(ReactiveCube cube);

		void initialize(U uplink);
	}

}
