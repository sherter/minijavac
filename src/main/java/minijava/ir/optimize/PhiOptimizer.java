package minijava.ir.optimize;

import static org.jooq.lambda.Seq.seq;

import firm.BackEdges;
import firm.Graph;
import firm.Mode;
import firm.nodes.Node;
import firm.nodes.Phi;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import minijava.ir.utils.FirmUtils;
import minijava.ir.utils.GraphUtils;

/**
 * Performs various optimizations special to Phi nodes. Currently, there are transformations for
 *
 * <ul>
 *   <li>Removing Phi nodes where all predecessors are the same.
 * </ul>
 */
public class PhiOptimizer extends BaseOptimizer {

  /** Maps Phis to replace to the node its def is replaced with. */
  private final Map<Node, Node> replacements = new HashMap<>();

  @Override
  public boolean optimize(Graph graph) {
    this.graph = graph;
    this.replacements.clear();
    fixedPointIteration(GraphUtils.reverseTopologicalOrder(graph));
    return FirmUtils.withBackEdges(graph, this::transform);
  }

  /** Essentially follows replacements, but also compresses paths. */
  private Node followReplacements(Node node) {
    if (replacements.containsKey(node)) {
      Node original = node;
      node = followReplacements(replacements.get(node));
      replacements.put(original, node);
    }
    return node;
  }

  @Override
  public void visit(Phi node) {
    removeSinglePredPhi(node);
  }

  private void removeSinglePredPhi(Phi phi) {
    // If the predecessor edges of a Phi all point to the same node, we can eliminate that Phi.
    // That's because the definition block must domininate every predecessor, so by definition
    // of dominance it also dominates this block.
    // There is the special case where a Phi has Phis as a predecessor. We can't really do this
    // transformation if both Phis are in the same block. But if that would be the case,
    // there is no way we could enter this block, because that phi won't be visible
    // from any predecessor, at least not on the first iteration.
    // Also we can ignore self references for this, if we still have only one real pred.
    Set<Node> predsOtherThanSelf =
        seq(phi.getPreds())
            .filter(
                n ->
                    phi.getMode().equals(Mode.getM())
                        || !followReplacements(n).equals(phi)) // Only include self loops in mode M
            .toSet();
    int distinctPreds = predsOtherThanSelf.size();
    boolean isOnlyPred = distinctPreds == 1;
    if (!isOnlyPred) {
      return;
    }
    Node pred = predsOtherThanSelf.iterator().next();

    boolean isKeptAlive = seq(graph.getEnd().getPreds()).contains(phi);
    if (isKeptAlive) {
      // We could try to find the predecessor index in End and remove it by replacing it with a Bad,
      // but that just seems like too much trouble for no gain: Kept alive nodes are (probably) always
      // Phi[loop] of mode M, which are erased by code gen.
      return;
    }

    assert !(pred instanceof Phi) || !pred.getBlock().equals(phi.getBlock());
    updateReplacement(phi, pred);
  }

  private void updateReplacement(Phi phi, Node pred) {
    Node oldValue = followReplacements(phi);
    replacements.put(phi, pred);
    Node newValue = followReplacements(phi);
    hasChanged |= !newValue.equals(oldValue);
  }

  private boolean transform() {
    for (Node phi : replacements.keySet()) {
      Node replacement = followReplacements(phi);
      for (BackEdges.Edge usage : BackEdges.getOuts(phi)) {
        usage.node.setPred(usage.pos, replacement);
      }
    }
    return !replacements.isEmpty();
  }
}
