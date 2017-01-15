package minijava.ir.optimize;

import static firm.bindings.binding_irnode.ir_opcode.iro_Address;
import static firm.bindings.binding_irnode.ir_opcode.iro_Const;
import static firm.bindings.binding_irnode.ir_opcode.iro_Phi;
import static org.jooq.lambda.Seq.seq;

import com.google.common.collect.Sets;
import firm.BackEdges;
import firm.Graph;
import firm.MethodType;
import firm.Mode;
import firm.bindings.binding_irnode.ir_opcode;
import firm.nodes.Address;
import firm.nodes.Block;
import firm.nodes.Call;
import firm.nodes.End;
import firm.nodes.Jmp;
import firm.nodes.Node;
import firm.nodes.Phi;
import firm.nodes.Proj;
import firm.nodes.Return;
import firm.nodes.Start;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import minijava.ir.utils.FirmUtils;
import minijava.ir.utils.GraphUtils;
import minijava.ir.utils.NodeUtils;
import org.jooq.lambda.tuple.Tuple2;

public class Inliner extends BaseOptimizer {
  private static final Set<ir_opcode> ALWAYS_IN_START_BLOCK =
      Sets.newHashSet(iro_Const, iro_Address);
  private final ProgramMetrics metrics;
  private final Set<Call> callsToInline = new HashSet<>();

  public Inliner(ProgramMetrics metrics) {
    this.metrics = metrics;
  }

  @Override
  public boolean optimize(Graph graph) {
    this.graph = graph;
    this.callsToInline.clear();
    fixedPointIteration();
    return inlineCandidates();
  }

  private boolean inlineCandidates() {
    boolean hasChanged = false;
    for (Call call : callsToInline) {
      //System.out.println("Inlining " + callee(call) + " into " + graph);
      inline(call);
      hasChanged = true;
    }
    return hasChanged;
  }

  private void inline(Call call) {
    Graph callee = callee(call);
    Tuple2<Start, End> subgraph = GraphUtils.copyGraph(callee, call.getGraph());
    Start start = subgraph.v1;
    End end = subgraph.v2;

    Block afterCallBlock = moveDependenciesIntoNewBlock(call);

    // We have to replace Projs to the Start node.
    // We can't yet use BackEdges since the subgraph is not yet connected to the graphs End node.
    Block startBlock = (Block) start.getBlock();

    Consumer<Node> onFinish =
        node -> {
          if (ALWAYS_IN_START_BLOCK.contains(node.getOpCode())) {
            // Const and Address nodes must always be placed in the start block.
            node.setBlock(graph.getStartBlock());
          } else if (startBlock.equals(node.getBlock())) {
            node.setBlock(call.getBlock());
          }

          if (!(node instanceof Proj)) {
            return;
          }

          Proj proj = (Proj) node;
          boolean isProjMOnStart =
              proj.getMode().equals(Mode.getM()) && proj.getPred().equals(start);
          if (isProjMOnStart) {
            Graph.exchange(proj, call.getPred(0));
            return;
          }

          boolean predIsTuple = proj.getPred().getMode().equals(Mode.getT());
          boolean predIsProj = proj.getPred() instanceof Proj;
          boolean predIsPredOfStart = predIsProj && proj.getPred().getPred(0).equals(start);
          boolean predIsArgsNode = predIsTuple && predIsProj && predIsPredOfStart;
          if (predIsArgsNode) {
            int argIndex = proj.getNum();
            // First pred to call is M, second is the functions Address
            Graph.exchange(proj, call.getPred(argIndex + 2));
          }
        };
    GraphUtils.walkFromNodeDepthFirst(end, n -> {}, onFinish);

    // The End node has to replaced by Phis, one for the ret val, the other for M.
    // Other than that, we have to do pretty much the same Proj substitution at the call site
    // as we did for the callee copy for the Start node.
    Block endBlock = (Block) end.getBlock();
    int returnNodes = endBlock.getPredCount();
    MethodType mt = (MethodType) call.getType();
    int returnVals = mt.getNRess() + 1; // index 0 is M
    Node[][] phiPreds = new Node[returnVals][returnNodes];
    for (int i = 0; i < returnNodes; ++i) {
      Return ret = (Return) endBlock.getPred(i);
      for (int r = 0; r < returnVals; ++r) {
        phiPreds[r][i] = ret.getPred(r);
      }
      Graph.exchange(ret, graph.newJmp(ret.getBlock()));
    }

    Phi[] phis = new Phi[phiPreds.length];
    for (int i = 0; i < phis.length; ++i) {
      Mode mode = i == 0 ? Mode.getM() : mt.getResType(i - 1).getMode();
      phis[i] = (Phi) graph.newPhi(endBlock, phiPreds[i], mode);
    }

    FirmUtils.withBackEdges(
        graph,
        () -> {
          for (Proj proj : seq(BackEdges.getOuts(call)).map(be -> be.node).ofType(Proj.class)) {
            if (proj.getMode().equals(Mode.getM())) {
              Graph.exchange(proj, phis[0]);
            } else {
              assert proj.getMode().equals(Mode.getT());
              for (Proj argProj :
                  seq(BackEdges.getOuts(proj)).map(be -> be.node).ofType(Proj.class)) {
                int argIndex = argProj.getNum();
                // First pred to a Return is M, the rest are the arguments
                Graph.exchange(argProj, phis[argIndex + 1]);
              }
            }
          }
        });

    afterCallBlock.setPred(0, graph.newJmp(endBlock));
  }

  private Block moveDependenciesIntoNewBlock(Node node) {
    Jmp jmp = (Jmp) node.getGraph().newJmp(node.getBlock());
    Block newBlock = (Block) node.getGraph().newBlock(new Node[] {jmp});
    Set<Node> toMove = new HashSet<>();

    FirmUtils.withBackEdges(
        node.getGraph(),
        () -> {
          Set<Node> toVisit = new HashSet<>();

          // the successors of the node itself should be checked
          toVisit.add(node);

          for (BackEdges.Edge edge : BackEdges.getOuts(node.getBlock())) {
            // ... as well as any control flow nodes.
            boolean controlFlowNode = edge.node.getMode().equals(Mode.getX());

            // Seems unnecessary, but this filters out keep edges
            boolean sameBlock = edge.node.getBlock().equals(node.getBlock());

            if (controlFlowNode && sameBlock) {
              toVisit.add(edge.node);

              // In case of Projs (e.g. conditional jumps), we also want to move the Cond node.
              // This is so we don't have to generate spill instructions for values of mode b.
              // Otherwise the FloatIn transformation should do this.
              NodeUtils.asProj(edge.node).ifPresent(proj -> toVisit.add(proj.getPred()));
            }
          }

          Set<Node> visited = new HashSet<>();
          while (!toVisit.isEmpty()) {
            Node move = seq(toVisit).findAny().get();
            toVisit.remove(move);
            if (visited.contains(move)) {
              continue;
            }
            visited.add(move);

            if (move.getOpCode().equals(iro_Phi)) {
              // We may not move Phis, as they are tied to their block.
              // That's not even bad, because we also don't have to move any successors.
              continue;
            }

            toMove.add(move);
            toVisit.addAll(
                seq(BackEdges.getOuts(move))
                    .map(be -> be.node)
                    .filter(n -> !toMove.contains(n))
                    .filter(n -> node.getBlock().equals(n.getBlock()))
                    .toList());
          }
          toMove.remove(node); // The node itself should remain in the old block
        });

    for (Node move : toMove) {
      if (move.getOpCode().equals(iro_Phi)) {
        // We don't move phi nodes, as they are tied to the block.
        // This case only happens in loops, where the PHi depends on the result of the call
        // and a value of the last iteration.
        continue;
      }
      move.setBlock(newBlock);
    }

    return newBlock;
  }

  @Override
  public void visit(Call call) {
    Graph callee = callee(call);
    if (callee == null) {
      // This was a foreign/native call, where we don't have access to the graph.
      return;
    }
    ProgramMetrics.GraphInfo calleeInfo = metrics.graphInfos.get(callee(call));
    if (!calleeInfo.calls.isEmpty()) {
      // We only inline if the callee itself doesn't call any other functions for now
      return;
    }
    if (calleeInfo.diverges) {
      // We could potentially inline this, but it won't bring any benefit, as the diverging loop
      // dominates the method call overhead.
      // In case we do want this though, we have to graph.keepAlive(end.getPred(1));
      return;
    }
    if (calleeInfo.size > 1000) {
      // This should be sufficiently high so that the call overhead isn't noticeable
      // (generally, imagine an if/else...).
      return;
    }
    callsToInline.add(call);
  }

  private Graph callee(Call call) {
    Address funcPtr = (Address) call.getPtr();
    return funcPtr.getEntity().getGraph();
  }
}