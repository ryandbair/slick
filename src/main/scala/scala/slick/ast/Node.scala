package scala.slick.ast

import scala.slick.SlickException
import slick.lifted.ShapedValue
import scala.slick.util.SimpleTypeName
import Util._

/** An object that can produce a Node. */
trait NodeGenerator {
  def nodeDelegate: Node
}

/**
 * A node in the query AST.
 *
 * Every Node has a number of child nodes and an optional type annotation.
 */
trait Node extends NodeGenerator {
  /** All child nodes of this node. Must be implemented by subclasses. */
  def nodeChildren: Seq[Node]

  /** Names for the child nodes to show in AST dumps. Defaults to a numbered
    * sequence starting at 0 but can be overridden by subclasses to produce
    * more suitable names. */
  def nodeChildNames: Iterable[String] = Stream.from(0).map(_.toString)

  protected[this] def nodeRebuild(ch: IndexedSeq[Node]): Node

  /** Apply a mapping function to all children of this node and recreate the
    * node with the new children. If all new children are identical to the old
    * ones, this node is returned. */
  final def nodeMapChildren(f: Node => Node): Node =
    mapOrNone(nodeChildren, f).map(nodeRebuild).getOrElse(this)

  def nodeDelegate: Node = this

  override def toString = this match {
    case p: Product =>
      val cln = getClass.getName.replaceFirst(".*\\.", "")
      val n = if(cln.endsWith("$")) cln.substring(0, cln.length-1) else cln.replaceFirst(".*\\$", "")
      val args = p.productIterator.filterNot(_.isInstanceOf[Node]).mkString(", ")
      if(args.isEmpty) n else (n + ' ' + args)
    case _ => super.toString
  }

  /** The intrinsic symbol that points to this Node object. */
  final def nodeIntrinsicSymbol = new IntrinsicSymbol(this)
}

trait TypedNode extends Node with Typed

object Node {
  def apply(o:Any): Node =
    if(o == null) LiteralNode(null)
    else if(o.isInstanceOf[WithOp] && (o.asInstanceOf[WithOp].op ne null)) Node(o.asInstanceOf[WithOp].op)
    else if(o.isInstanceOf[NodeGenerator]) {
      val gen = o.asInstanceOf[NodeGenerator]
      if(gen.nodeDelegate eq gen) gen.nodeDelegate else Node(gen.nodeDelegate)
    }
    else if(o.isInstanceOf[Product]) ProductNode(o.asInstanceOf[Product].productIterator.toSeq)
    else throw new SlickException("Cannot narrow "+o+" of type "+SimpleTypeName.forVal(o)+" to a Node")
}

/** An expression that represents a conjunction of expressions. */
trait ProductNode extends Node {
  override def toString = "ProductNode"
  protected[this] def nodeRebuild(ch: IndexedSeq[Node]): Node = new ProductNode {
    val nodeChildren = ch
  }
  override def nodeChildNames: Iterable[String] = Stream.from(1).map(_.toString)
  override def hashCode() = nodeChildren.hashCode()
  override def equals(o: Any) = o match {
    case p: ProductNode => nodeChildren == p.nodeChildren
    case _ => false
  }
}

object ProductNode {
  def apply(s: Seq[Any]): ProductNode =
    new ProductNode { lazy val nodeChildren = s.map(Node(_)) }
  def unapply(p: ProductNode) = Some(p.nodeChildren)
}

/** An expression that represents a structure, i.e. a conjunction where the
  * individual components have Symbols associated with them. */
final case class StructNode(elements: IndexedSeq[(Symbol, Node)]) extends ProductNode with DefNode {
  override def toString = "StructNode"
  override def nodeChildNames = elements.map(_._1.toString)
  val nodeChildren = elements.map(_._2)
  override protected[this] def nodeRebuild(ch: IndexedSeq[Node]) =
    new StructNode(elements.zip(ch).map{ case ((s,_),n) => (s,n) })
  override def hashCode() = elements.hashCode()
  override def equals(o: Any) = o match {
    case s: StructNode => elements == s.elements
    case _ => false
  }
  def nodeGenerators = elements
  override def nodePostGeneratorChildren = Seq.empty // for efficiency
  protected[this] def nodeRebuildWithGenerators(gen: IndexedSeq[Symbol]): Node =
    copy(elements = (elements, gen).zipped.map((e, s) => (s, e._2)))
}

/** A literal value expression. */
trait LiteralNode extends NullaryNode with TypedNode {
  def value: Any
}

object LiteralNode {
  def apply(tp: Type, v: Any): LiteralNode = new LiteralNode {
    val value = v
    val tpe = tp
  }
  def apply[T](v: T)(implicit tp: StaticType[T]): LiteralNode = apply(tp, v)
  def unapply(n: LiteralNode): Option[Any] = Some(n.value)
}

trait BinaryNode extends Node {
  def left: Node
  def right: Node
  lazy val nodeChildren = Seq(left, right)
  protected[this] final def nodeRebuild(ch: IndexedSeq[Node]): Node = nodeRebuild(ch(0), ch(1))
  protected[this] def nodeRebuild(left: Node, right: Node): Node
}

trait UnaryNode extends Node {
  def child: Node
  lazy val nodeChildren = Seq(child)
  protected[this] final def nodeRebuild(ch: IndexedSeq[Node]): Node = nodeRebuild(ch(0))
  protected[this] def nodeRebuild(child: Node): Node
}

trait NullaryNode extends Node {
  val nodeChildren = Nil
  protected[this] final def nodeRebuild(ch: IndexedSeq[Node]): Node = this
}

/** An expression that represents a plain value lifted into a Query. */
final case class Pure(value: Node) extends UnaryNode {
  def child = value
  override def nodeChildNames = Seq("value")
  protected[this] def nodeRebuild(child: Node) = copy(value = child)
}

/** Common superclass for expressions of type
  * (CollectionType(c, t), _) => CollectionType(c, t). */
abstract class FilteredQuery extends DefNode {
  def generator: Symbol
  def from: Node
  def nodeGenerators = Seq((generator, from))
  def nodeMapFrom(f: Node => Node) = {
    val fr = from
    nodeMapChildren(n => if(n eq fr) f(n) else n)
  }
  def nodeMapOthers(f: Node => Node) = {
    val fr = from
    nodeMapChildren(n => if(n ne fr) f(n) else n)
  }
  override def toString = this match {
    case p: Product =>
      val n = getClass.getName.replaceFirst(".*\\.", "").replaceFirst(".*\\$", "")
      val args = p.productIterator.filterNot(n => n.isInstanceOf[Node] || n.isInstanceOf[Symbol]).mkString(", ")
      if(args.isEmpty) n else (n + ' ' + args)
    case _ => super.toString
  }
}

object FilteredQuery {
  def unapply(f: FilteredQuery) = Some((f.generator, f.from))
}

/** A .filter call of type
  * (CollectionType(c, t), Boolean) => CollectionType(c, t). */
final case class Filter(generator: Symbol, from: Node, where: Node) extends FilteredQuery with BinaryNode {
  def left = from
  def right = where
  override def nodeChildNames = Seq("from "+generator, "where")
  protected[this] def nodeRebuild(left: Node, right: Node) = copy(from = left, where = right)
  override def nodeDelegate =
    if(where match { case LiteralNode(true) => true; case _ => false }) left
    else super.nodeDelegate
  protected[this] def nodeRebuildWithGenerators(gen: IndexedSeq[Symbol]) = copy(generator = gen(0))
}

/** A .sortBy call of type
  * (CollectionType(c, t), _) => CollectionType(c, t). */
final case class SortBy(generator: Symbol, from: Node, by: Seq[(Node, Ordering)]) extends FilteredQuery {
  lazy val nodeChildren = from +: by.map(_._1)
  protected[this] def nodeRebuild(ch: IndexedSeq[Node]) =
    copy(from = ch(0), by = by.zip(ch.tail).map{ case ((_, o), n) => (n, o) })
  override def nodeChildNames = ("from "+generator) +: by.zipWithIndex.map("by" + _._2)
  protected[this] def nodeRebuildWithGenerators(gen: IndexedSeq[Symbol]) = copy(generator = gen(0))
  override def toString = "SortBy " + by.map(_._2).mkString(", ")
}

final case class Ordering(direction: Ordering.Direction = Ordering.Asc, nulls: Ordering.NullOrdering = Ordering.NullsDefault) {
  def asc = copy(direction = Ordering.Asc)
  def desc = copy(direction = Ordering.Desc)
  def reverse = copy(direction = direction.reverse)
  def nullsDefault = copy(nulls = Ordering.NullsDefault)
  def nullsFirst = copy(nulls = Ordering.NullsFirst)
  def nullsLast = copy(nulls = Ordering.NullsLast)
}

object Ordering {
  sealed abstract class NullOrdering(val first: Boolean, val last: Boolean)
  final case object NullsDefault extends NullOrdering(false, false)
  final case object NullsFirst extends NullOrdering(true, false)
  final case object NullsLast extends NullOrdering(false, true)

  sealed abstract class Direction(val desc: Boolean) { def reverse: Direction }
  final case object Asc extends Direction(false) { def reverse = Desc }
  final case object Desc extends Direction(true) { def reverse = Asc }
}

/** A .groupBy call. */
final case class GroupBy(fromGen: Symbol, byGen: Symbol, from: Node, by: Node) extends BinaryNode with DefNode {
  def left = from
  def right = by
  override def nodeChildNames = Seq("from "+fromGen, "by "+byGen)
  protected[this] def nodeRebuild(left: Node, right: Node) = copy(from = left, by = right)
  protected[this] def nodeRebuildWithGenerators(gen: IndexedSeq[Symbol]) = copy(fromGen = gen(0), byGen = gen(1))
  def nodeGenerators = Seq((fromGen, from), (byGen, by))
  override def toString = "GroupBy"
}

/** A .take call. */
final case class Take(from: Node, num: Int, generator: Symbol = new AnonSymbol) extends FilteredQuery with UnaryNode {
  def child = from
  override def nodeChildNames = Seq("from "+generator)
  protected[this] def nodeRebuild(child: Node) = copy(from = child)
  protected[this] def nodeRebuildWithGenerators(gen: IndexedSeq[Symbol]) = copy(generator = gen(0))
}

/** A .drop call. */
final case class Drop(from: Node, num: Int, generator: Symbol = new AnonSymbol) extends FilteredQuery with UnaryNode {
  def child = from
  override def nodeChildNames = Seq("from "+generator)
  protected[this] def nodeRebuild(child: Node) = copy(from = child)
  protected[this] def nodeRebuildWithGenerators(gen: IndexedSeq[Symbol]) = copy(generator = gen(0))
}

/** A join expression of type
  * (CollectionType(c, t), CollectionType(_, u)) => CollecionType(c, (t, u)). */
final case class Join(leftGen: Symbol, rightGen: Symbol, left: Node, right: Node, jt: JoinType, on: Node) extends DefNode {
  lazy val nodeChildren = IndexedSeq(left, right, on)
  protected[this] def nodeRebuild(ch: IndexedSeq[Node]) = copy(left = ch(0), right = ch(1), on = ch(2))
  override def nodeChildNames = Seq("left "+leftGen, "right "+rightGen, "on")
  override def toString = "Join " + jt.sqlName
  def nodeGenerators = Seq((leftGen, left), (rightGen, right))
  protected[this] def nodeRebuildWithGenerators(gen: IndexedSeq[Symbol]) = copy(leftGen = gen(0), rightGen = gen(1))
  def nodeCopyJoin(leftGen: Symbol = leftGen, rightGen: Symbol = rightGen, left: Node = left, right: Node = right, jt: JoinType = jt) = {
    if((leftGen eq this.leftGen) && (rightGen eq this.rightGen) && (left eq this.left) && (right eq this.right) && (jt eq this.jt)) this
    else copy(leftGen = leftGen, rightGen = rightGen, left = left, right = right, jt = jt)
  }
}

/** A union of type
  * (CollectionType(c, t), CollectionType(_, t)) => CollectionType(c, t). */
final case class Union(left: Node, right: Node, all: Boolean, leftGen: Symbol = new AnonSymbol, rightGen: Symbol = new AnonSymbol) extends BinaryNode with DefNode {
  protected[this] def nodeRebuild(left: Node, right: Node) = copy(left = left, right = right)
  override def toString = if(all) "Union all" else "Union"
  override def nodeChildNames = Seq("left "+leftGen, "right "+rightGen)
  def nodeGenerators = Seq((leftGen, left), (rightGen, right))
  protected[this] def nodeRebuildWithGenerators(gen: IndexedSeq[Symbol]) = copy(leftGen = gen(0), rightGen = gen(1))
}

/** A .flatMap call of type
  * (CollectionType(c, _), CollectionType(_, u)) => CollectionType(c, u). */
final case class Bind(generator: Symbol, from: Node, select: Node) extends BinaryNode with DefNode {
  def left = from
  def right = select
  override def nodeChildNames = Seq("from "+generator, "select")
  protected[this] def nodeRebuild(left: Node, right: Node) = copy(from = left, select = right)
  def nodeGenerators = Seq((generator, from))
  override def toString = "Bind"
  protected[this] def nodeRebuildWithGenerators(gen: IndexedSeq[Symbol]) = copy(generator = gen(0))
}

/** A table expansion. In phase expandTables, all tables are replaced by
  * TableExpansions to capture the dual nature of tables as as single entity
  * and a structure of columns. TableExpansions are removed again in phase
  * rewritePaths. */
final case class TableExpansion(generator: Symbol, table: Node, columns: Node) extends BinaryNode with DefNode {
  def left = table
  def right = columns
  override def nodeChildNames = Seq("table "+generator, "columns")
  protected[this] def nodeRebuild(left: Node, right: Node) = copy(table = left, columns = right)
  def nodeGenerators = Seq((generator, table))
  override def toString = "TableExpansion"
  protected[this] def nodeRebuildWithGenerators(gen: IndexedSeq[Symbol]) = copy(generator = gen(0))
}

/** Similar to a TableExpansion but used to replace a Ref pointing to a
  * Table(Expansion) (or another TableRefExpansion) instead of a plain Table. */
final case class TableRefExpansion(marker: Symbol, ref: Node, columns: Node) extends BinaryNode with DefNode {
  def left = ref
  def right = columns
  override def nodeChildNames = Seq("ref", "columns")
  protected[this] def nodeRebuild(left: Node, right: Node) = copy(ref = left, columns = right)
  def nodeGenerators = Seq((marker, ref))
  override def toString = "TableRefExpansion "+marker
  protected[this] def nodeRebuildWithGenerators(gen: IndexedSeq[Symbol]) = copy(marker = gen(0))
}

final case class Select(in: Node, field: Symbol) extends UnaryNode with RefNode {
  /** An expression that selects a field in another expression. */
  if(in.isInstanceOf[TableNode])
    throw new SlickException("Select(TableNode, \""+field+"\") found. This is "+
      "typically caused by an attempt to use a \"raw\" table object directly "+
      "in a query without introducing it through a generator.")
  def child = in
  override def nodeChildNames = Seq("in")
  protected[this] def nodeRebuild(child: Node) = copy(in = child)
  def nodeReference = field
  protected[this] def nodeRebuildWithReference(s: Symbol) = copy(field = s)
  override def toString = Path.unapply(this) match {
    case Some(l) => super.toString + " for " + Path.toString(l)
    case None => super.toString
  }
}

/** A function call expression. */
case class Apply(sym: Symbol, children: Seq[Node]) extends RefNode {
  def nodeChildren = children
  protected[this] def nodeRebuild(ch: IndexedSeq[scala.slick.ast.Node]) = copy(children = ch)
  def nodeReference = sym
  protected[this] def nodeRebuildWithReference(s: Symbol) = copy(sym = s)
  override def toString = "Apply "+sym
}

object Apply {
  /** Create a typed Apply */
  def apply(sym: Symbol, children: Seq[Node], tp: Type): Apply with TypedNode =
    new Apply(sym, children) with TypedNode {
      def tpe = tp
      override protected[this] def nodeRebuild(ch: IndexedSeq[scala.slick.ast.Node]) = Apply(sym, ch, tp)
      override protected[this] def nodeRebuildWithReference(s: Symbol) = Apply(s, children, tp)
    }
}

/** A reference to a Symbol */
case class Ref(sym: Symbol) extends NullaryNode with RefNode {
  def nodeReference = sym
  protected[this] def nodeRebuildWithReference(s: Symbol) = copy(sym = s)
}

/** A constructor/extractor for nested Selects starting at a Ref. */
object Path {
  def apply(l: List[Symbol]): Node = l match {
    case s :: Nil => Ref(s)
    case s :: l => Select(apply(l), s)
  }
  def unapply(n: Node): Option[List[Symbol]] = n match {
    case Ref(sym) => Some(List(sym))
    case Select(in, s) => unapply(in).map(l => s :: l)
    case _ => None
  }
  def toString(path: Seq[Symbol]): String = path.reverseIterator.mkString("Path ", ".", "")
  def toString(s: Select): String = s match {
    case Path(syms) => toString(syms)
    case n => n.toString
  }
}

/** Base class for table nodes. Direct and lifted embedding have different
  * implementations of this class. */
abstract class TableNode extends Node {
  def nodeShaped_* : ShapedValue[_, _]
  def tableName: String
  def nodeTableSymbol: TableSymbol = TableSymbol(tableName)
  override def toString = "Table " + tableName
}

object TableNode {
  def unapply(t: TableNode) = Some(t.tableName)
}

/** A dynamically scoped Let expression where the resulting expression and
  * all definitions may refer to other definitions independent of the order
  * in which they appear in the Let. Circular dependencies are not allowed. */
final case class LetDynamic(defs: Seq[(Symbol, Node)], in: Node) extends DefNode {
  val nodeChildren = defs.map(_._2) :+ in
  protected[this] def nodeRebuild(ch: IndexedSeq[Node]) =
    copy(defs = defs.zip(ch.init).map{ case ((s, _), n) => (s, n) }, in = ch.last)
  override def nodeChildNames = defs.map("let " + _._1.toString) :+ "in"
  def nodeGenerators = defs
  protected[this] def nodeRebuildWithGenerators(gen: IndexedSeq[Symbol]): Node =
    copy(defs = (defs, gen).zipped.map((e, s) => (s, e._2)))
  override def toString = "LetDynamic"
}

/** A node that represents an SQL sequence. */
final case class SequenceNode(name: String)(val increment: Long) extends NullaryNode

/** A Query of this special Node represents an infinite stream of consecutive
  * numbers starting at the given number. This is used as an operand for
  * zipWithIndex. It is not exposed directly in the query language because it
  * cannot be represented in SQL outside of a 'zip' operation. */
final case class RangeFrom(start: Long = 1L) extends NullaryNode

/** An if-then part of a Conditional node */
final case class IfThen(val left: Node, val right: Node) extends BinaryNode {
  protected[this] def nodeRebuild(left: Node, right: Node): Node = copy(left = left, right = right)
}

/** A conditional expression; all clauses should be IfThen nodes */
final case class ConditionalExpr(val clauses: IndexedSeq[Node], val elseClause: Node) extends Node {
  val nodeChildren = elseClause +: clauses
  protected[this] def nodeRebuild(ch: IndexedSeq[Node]) =
    copy(clauses = ch.tail, elseClause = ch.head)
}
