// code by ynager and jph
package ch.ethz.idsc.gokart.core.pure;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import ch.ethz.idsc.gokart.core.joy.JoystickConfig;
import ch.ethz.idsc.gokart.core.map.GokartMappingModule;
import ch.ethz.idsc.gokart.core.pos.GokartPoseEvent;
import ch.ethz.idsc.gokart.core.pos.GokartPoseHelper;
import ch.ethz.idsc.gokart.core.pos.GokartPoseLcmClient;
import ch.ethz.idsc.gokart.core.pos.GokartPoseListener;
import ch.ethz.idsc.gokart.core.pos.LocalizationConfig;
import ch.ethz.idsc.gokart.core.slam.PredefinedMap;
import ch.ethz.idsc.gokart.gui.top.ChassisGeometry;
import ch.ethz.idsc.gokart.lcm.autobox.RimoGetLcmClient;
import ch.ethz.idsc.gokart.lcm.mod.PlannerPublish;
import ch.ethz.idsc.owl.bot.r2.WaypointDistanceCost;
import ch.ethz.idsc.owl.bot.se2.Se2CarIntegrator;
import ch.ethz.idsc.owl.bot.se2.Se2ComboRegion;
import ch.ethz.idsc.owl.bot.se2.Se2MinTimeGoalManager;
import ch.ethz.idsc.owl.bot.se2.Se2PointsVsRegions;
import ch.ethz.idsc.owl.bot.se2.Se2Wrap;
import ch.ethz.idsc.owl.bot.se2.glc.Se2CarFlows;
import ch.ethz.idsc.owl.bot.util.FlowsInterface;
import ch.ethz.idsc.owl.car.core.VehicleModel;
import ch.ethz.idsc.owl.car.shop.RimoSinusIonModel;
import ch.ethz.idsc.owl.data.Lists;
import ch.ethz.idsc.owl.glc.adapter.EtaRaster;
import ch.ethz.idsc.owl.glc.adapter.Expand;
import ch.ethz.idsc.owl.glc.adapter.GlcTrajectories;
import ch.ethz.idsc.owl.glc.adapter.RegionConstraints;
import ch.ethz.idsc.owl.glc.adapter.Trajectories;
import ch.ethz.idsc.owl.glc.adapter.VectorCostGoalAdapter;
import ch.ethz.idsc.owl.glc.core.CostFunction;
import ch.ethz.idsc.owl.glc.core.GlcNode;
import ch.ethz.idsc.owl.glc.core.GoalInterface;
import ch.ethz.idsc.owl.glc.core.StateTimeRaster;
import ch.ethz.idsc.owl.glc.core.TrajectoryPlanner;
import ch.ethz.idsc.owl.glc.std.LexicographicRelabelDecision;
import ch.ethz.idsc.owl.glc.std.PlannerConstraint;
import ch.ethz.idsc.owl.glc.std.StandardTrajectoryPlanner;
import ch.ethz.idsc.owl.math.MinMax;
import ch.ethz.idsc.owl.math.StateTimeTensorFunction;
import ch.ethz.idsc.owl.math.flow.Flow;
import ch.ethz.idsc.owl.math.region.ImageRegion;
import ch.ethz.idsc.owl.math.region.Region;
import ch.ethz.idsc.owl.math.region.RegionUnion;
import ch.ethz.idsc.owl.math.state.FixedStateIntegrator;
import ch.ethz.idsc.owl.math.state.StateTime;
import ch.ethz.idsc.owl.math.state.TrajectorySample;
import ch.ethz.idsc.owl.subdiv.curve.BSpline1CurveSubdivision;
import ch.ethz.idsc.owl.subdiv.curve.Se2Geodesic;
import ch.ethz.idsc.retina.dev.joystick.GokartJoystickInterface;
import ch.ethz.idsc.retina.dev.joystick.JoystickEvent;
import ch.ethz.idsc.retina.dev.rimo.RimoGetEvent;
import ch.ethz.idsc.retina.dev.rimo.RimoGetListener;
import ch.ethz.idsc.retina.lcm.joystick.JoystickLcmProvider;
import ch.ethz.idsc.retina.sys.AbstractClockedModule;
import ch.ethz.idsc.retina.util.math.Magnitude;
import ch.ethz.idsc.tensor.RationalScalar;
import ch.ethz.idsc.tensor.RealScalar;
import ch.ethz.idsc.tensor.Scalar;
import ch.ethz.idsc.tensor.Scalars;
import ch.ethz.idsc.tensor.Tensor;
import ch.ethz.idsc.tensor.Tensors;
import ch.ethz.idsc.tensor.alg.Subdivide;
import ch.ethz.idsc.tensor.qty.Degree;
import ch.ethz.idsc.tensor.red.ArgMin;
import ch.ethz.idsc.tensor.red.Nest;
import ch.ethz.idsc.tensor.sca.Sign;
import ch.ethz.idsc.tensor.sca.Sqrt;

// TODO make configurable as parameter
public class GokartTrajectoryModule extends AbstractClockedModule {
  private static final VehicleModel STANDARD = RimoSinusIonModel.standard();
  private static final Tensor PARTITIONSCALE = Tensors.of( //
      RealScalar.of(2), RealScalar.of(2), Degree.of(10).reciprocal()).unmodifiable();
  private static final Scalar SQRT2 = Sqrt.of(RealScalar.of(2));
  private static final Scalar SPEED = RealScalar.of(2.5);
  private static final FixedStateIntegrator FIXED_STATE_INTEGRATOR = //
      FixedStateIntegrator.create(Se2CarIntegrator.INSTANCE, RationalScalar.of(2, 10), 4);
  private static final Se2Wrap SE2WRAP = new Se2Wrap(Tensors.vector(1, 1, 2));
  private static final StateTimeRaster STATE_TIME_RASTER = //
      new EtaRaster(PARTITIONSCALE, StateTimeTensorFunction.state(SE2WRAP::represent));
  // ---
  final FlowsInterface flowsInterface = Se2CarFlows.forward( //
      SPEED, Magnitude.PER_METER.apply(TrajectoryConfig.GLOBAL.maxRotation));
  private final GokartPoseLcmClient gokartPoseLcmClient = new GokartPoseLcmClient();
  private final RimoGetLcmClient rimoGetLcmClient = new RimoGetLcmClient();
  private final JoystickLcmProvider joystickLcmProvider = JoystickConfig.GLOBAL.createProvider();
  final PurePursuitModule purePursuitModule = new PurePursuitModule();
  private final GokartMappingModule gokartMappingModule = new GokartMappingModule();
  private GokartPoseEvent gokartPoseEvent = null;
  private List<TrajectorySample> trajectory = null;
  final Tensor waypoints = TrajectoryConfig.getWaypoints();
  private PlannerConstraint plannerConstraint;
  private final Tensor goalRadius;
  private Region<Tensor> unionRegion;
  private Scalar tangentSpeed = null;
  // TODO magic const redundant
  private final CostFunction waypointCost = WaypointDistanceCost.linear( //
      Nest.of(new BSpline1CurveSubdivision(Se2Geodesic.INSTANCE)::cyclic, waypoints, 1), // 1 round of refinement
      Tensors.vector(85.33, 85.33), 8.0f, new Dimension(640, 640));
  private final GokartPoseListener gokartPoseListener = new GokartPoseListener() {
    @Override
    public void getEvent(GokartPoseEvent getEvent) { // arrives at 50[Hz]
      gokartPoseEvent = getEvent;
    }
  };
  private final RimoGetListener rimoGetListener = new RimoGetListener() {
    @Override
    public void getEvent(RimoGetEvent getEvent) {
      tangentSpeed = ChassisGeometry.GLOBAL.odometryTangentSpeed(getEvent);
    }
  };

  public GokartTrajectoryModule() {
    MinMax minMax = MinMax.of(STANDARD.footprint());
    Tensor x_samples = Subdivide.of(minMax.min().get(0), minMax.max().get(0), 2); // {-0.295, 0.7349999999999999, 1.765}
    PredefinedMap predefinedMap = LocalizationConfig.getPredefinedMapObstacles();
    ImageRegion imageRegion = predefinedMap.getImageRegion();
    // ---
    unionRegion = RegionUnion.wrap(Arrays.asList( //
        Se2PointsVsRegions.line(x_samples, imageRegion), gokartMappingModule));
    plannerConstraint = RegionConstraints.timeInvariant(unionRegion);
    // ---
    final Scalar goalRadius_xy = SQRT2.divide(PARTITIONSCALE.Get(0));
    final Scalar goalRadius_theta = SQRT2.divide(PARTITIONSCALE.Get(2));
    goalRadius = Tensors.of(goalRadius_xy, goalRadius_xy, goalRadius_theta);
  }

  @Override // from AbstractClockedModule
  protected void first() throws Exception {
    gokartMappingModule.start();
    // ---
    gokartPoseLcmClient.addListener(gokartPoseListener);
    rimoGetLcmClient.addListener(rimoGetListener);
    // ---
    gokartPoseLcmClient.startSubscriptions();
    joystickLcmProvider.startSubscriptions();
    rimoGetLcmClient.startSubscriptions();
    // ---
    purePursuitModule.launch();
  }

  @Override // from AbstractClockedModule
  protected void last() {
    purePursuitModule.terminate();
    gokartPoseLcmClient.stopSubscriptions();
    joystickLcmProvider.stopSubscriptions();
    // ---
    gokartMappingModule.stop();
  }

  @Override // from AbstractClockedModule
  protected void runAlgo() {
    gokartMappingModule.prepareMap();
    Scalar tangentSpeed_ = tangentSpeed;
    if (Objects.nonNull(gokartPoseEvent) && Objects.nonNull(tangentSpeed_)) {
      System.out.println("setup planner");
      final Tensor xya = GokartPoseHelper.toUnitless(gokartPoseEvent.getPose()).unmodifiable();
      final List<TrajectorySample> head;
      Optional<JoystickEvent> optional = joystickLcmProvider.getJoystick();
      boolean isResetPressed = optional.isPresent() && ((GokartJoystickInterface) optional.get()).isResetPressed();
      if (Objects.isNull(trajectory) || isResetPressed) { // exists previous trajectory?
        // no: plan from current position
        StateTime stateTime = new StateTime(xya, RealScalar.ZERO);
        head = Arrays.asList(TrajectorySample.head(stateTime));
      } else {
        // yes: plan from closest point + cutoffDist on previous trajectory
        Scalar cutoffDist = TrajectoryConfig.GLOBAL.getCutoffDistance(tangentSpeed_);
        head = getTrajectoryUntil(trajectory, xya, Magnitude.METER.apply(cutoffDist));
      }
      Tensor distances = Tensor.of(waypoints.stream().map(wp -> SE2WRAP.distance(wp, xya)));
      int wpIdx = ArgMin.of(distances); // find closest waypoint to current position
      if (0 <= wpIdx && !head.isEmpty()) { // jan inserted check for non-empty
        Tensor goal = waypoints.get(wpIdx);
        // find a goal waypoint that is located beyond horizonDistance & does not lie within obstacle
        while (Scalars.lessThan(SE2WRAP.distance(xya, goal), TrajectoryConfig.GLOBAL.horizonDistance) || unionRegion.isMember(goal)) {
          wpIdx = (wpIdx + 1) % waypoints.length();
          goal = waypoints.get(wpIdx);
        }
        // System.out.format("goal index = " + wpIdx + ", distance = %.2f \n", SE2WRAP.distance(xya, goal).number().floatValue());
        int resolution = TrajectoryConfig.GLOBAL.controlResolution.number().intValue();
        Collection<Flow> controls = flowsInterface.getFlows(resolution);
        Se2ComboRegion se2ComboRegion = //
            Se2ComboRegion.cone(goal, TrajectoryConfig.GLOBAL.coneHalfAngle, goalRadius.Get(2));
        // TODO spherical goal region works on gokart but tests fail
        // Se2ComboRegion.spherical(goal, Tensors.vector(2.5, 2.5, goalRadius.Get(2).number().doubleValue()));
        // ---
        // GoalInterface goalInterface = new Se2MinTimeGoalManager(se2ComboRegion, controls).getGoalInterface();
        // GoalInterface multiCostGoalInterface = MultiCostGoalAdapter.of(goalInterface, costCollection);
        List<CostFunction> costs = new ArrayList<>();
        costs.add(waypointCost);
        costs.add(new Se2MinTimeGoalManager(se2ComboRegion, controls));
        GoalInterface multiCostGoalInterface = new VectorCostGoalAdapter(costs, se2ComboRegion);
        TrajectoryPlanner trajectoryPlanner = new StandardTrajectoryPlanner( //
            STATE_TIME_RASTER, FIXED_STATE_INTEGRATOR, controls, plannerConstraint, multiCostGoalInterface);
        ((StandardTrajectoryPlanner) trajectoryPlanner).relabelDecision = //
            new LexicographicRelabelDecision(Tensors.vector(0, 0));
        // Do Planning
        StateTime root = Lists.getLast(head).stateTime(); // non-empty due to check above
        trajectoryPlanner.insertRoot(root);
        Expand.maxTime(trajectoryPlanner, TrajectoryConfig.GLOBAL.expandTimeLimit());
        expandResult(head, trajectoryPlanner); // build detailed trajectory and pass to purePursuit
        return;
      }
    }
    purePursuitModule.setCurve(Optional.empty());
    System.err.println("no curve because no pose");
  }

  /** @param trajectory
   * @param pose
   * @param cutoffDistHead non-negative unit-less
   * @return
   * @throws Exception if cutoffDistHead is negative */
  private static List<TrajectorySample> getTrajectoryUntil( //
      List<TrajectorySample> trajectory, Tensor pose, Scalar cutoffDistHead) {
    Sign.requirePositiveOrZero(cutoffDistHead);
    Tensor distances = Tensor.of(trajectory.stream().map(st -> SE2WRAP.distance(st.stateTime().state(), pose)));
    int closestIdx = ArgMin.of(distances);
    Tensor closest = trajectory.get(closestIdx).stateTime().state();
    return trajectory.stream() //
        .skip(Math.max((closestIdx - 5), 0)) // TODO magic const
        .filter(trajectorySample -> Scalars.lessEquals( //
            SE2WRAP.distance(closest, trajectorySample.stateTime().state()), cutoffDistHead)) //
        .collect(Collectors.toList());
  }

  @Override
  protected final Scalar getPeriod() {
    return TrajectoryConfig.GLOBAL.planningPeriod;
  }

  public void expandResult(List<TrajectorySample> head, TrajectoryPlanner trajectoryPlanner) {
    Optional<GlcNode> optional = trajectoryPlanner.getBest();
    if (optional.isPresent()) { // goal reached
      List<TrajectorySample> tail = //
          GlcTrajectories.detailedTrajectoryTo(trajectoryPlanner.getStateIntegrator(), optional.get());
      trajectory = Trajectories.glue(head, tail);
      Tensor curve = Tensor.of(trajectory.stream() //
          .map(trajectorySample -> trajectorySample.stateTime().state().extract(0, 2)));
      purePursuitModule.setCurve(Optional.of(curve));
      PlannerPublish.publishTrajectory(trajectory);
    } else {
      // failure to reach goal
      purePursuitModule.setCurve(Optional.empty());
      PlannerPublish.publishTrajectory(new ArrayList<>());
    }
  }
}
