package ru.circumflex.orm

import java.sql.Connection
import JDBC._
import ORM._

// ## DDL stuff

/**
 * A Unit-of-Work for generating database schema.
 */
class DDLUnit {
  import DDLUnit._

  // ### Objects

  protected var _schemata: Seq[Schema] = Nil
  protected var _tables: Seq[Table[_]] = Nil
  protected var _views: Seq[View[_]] = Nil
  protected var _constraints: Seq[Constraint[_]] = Nil
  protected var _indexes: Seq[Index[_]] = Nil
  protected var _preAuxs: Seq[SchemaObject] = Nil
  protected var _postAuxs: Seq[SchemaObject] = Nil

  def schemata = _schemata
  def tables = _tables
  def views = _views
  def constraints = _constraints
  def indexes = _indexes
  def preAuxs = _preAuxs
  def postAuxs = _postAuxs

  protected var _msgs: Seq[Msg] = Nil
  def messages = _msgs
  def msgsArray: Array[Msg] = messages.toArray

  def this(objects: SchemaObject*) = {
    this()
    add(objects: _*)
  }

  def resetMsgs(): this.type = {
    _msgs = Nil
    this
  }

  def clear() = {
    _schemata = Nil
    _tables = Nil
    _views = Nil
    _constraints = Nil
    _preAuxs = Nil
    _postAuxs = Nil
    resetMsgs()
  }

  def add(objects: SchemaObject*): this.type = {
    objects foreach addObject
    this
  }

  def addObject(obj: SchemaObject): this.type = {
    def processRelation(r: Relation[_]) {
      addObject(r.schema)
      _preAuxs ++= (r.preAux filter (!_preAuxs.contains(_)))
      r.postAux foreach addObject
    }

    obj match {
      case t: Table[_] =>
        if (!_tables.contains(t)) {
          _tables :+= t
          t.constraints foreach addObject
          t.indexes foreach addObject
          processRelation(t)
        }
      case v: View[_] =>
        if (!_views.contains(v)) {
          _views :+= v
          processRelation(v)
        }
      case c: Constraint[_] =>
        if (!_constraints.contains(c))
          _constraints :+= c
      case s: Schema =>
        if (!_schemata.contains(s))
          _schemata :+= s
      case o =>
        if (!_postAuxs.contains(o))
          _postAuxs :+= o
    }
    this
  }

  // ### Workers

  protected def dropObjects(objects: Seq[SchemaObject], conn: Connection) =
    for (o <- objects.reverse) {
      if (o.isInstanceOf[Relation[_]]) o.asInstanceOf[Relation[_]].invalideCaches

      val sql = o.sqlDrop
      ormLog.info(sql)
      autoClose(conn.prepareStatement(sql)){st =>
        st.executeUpdate
        _msgs :+= InfoMsg("DROP "  + o.objectName + ": OK", sql)
      }(e =>
        _msgs :+= ErrorMsg("DROP " + o.objectName + ": " + e.getMessage, sql))
    }

  protected def createObjects(objects: Seq[SchemaObject], conn: Connection) =
    for (o <- objects) {
      val sql = o.sqlCreate
      ormLog.info(sql)
      autoClose(conn.prepareStatement(sql)){st =>
        st.executeUpdate
        _msgs :+= InfoMsg("CREATE " + o.objectName + ": OK", sql)
      }(e =>
        _msgs :+= ErrorMsg("CREATE " + o.objectName + ": " + e.getMessage, sql))
    }
  /**
   * Execute a DROP script for added objects.
   */
  def drop(): this.type = {
    resetMsgs()
    _drop()
  }
  def _drop(): this.type = auto(tx.connection)(conn => {
      // We will commit every successfull statement.
      val autoCommit = conn.getAutoCommit
      conn.setAutoCommit(true)
      // Execute a script.
      dropObjects(postAuxs, conn)
      dropObjects(views, conn)
      if (dialect.supportsDropConstraints_?) dropObjects(constraints, conn)
      dropObjects(tables, conn)
      dropObjects(preAuxs, conn)
      if (dialect.supportsSchema_?) dropObjects(schemata, conn)
      // Restore auto-commit.
      conn.setAutoCommit(autoCommit)
      return this
    })

  /**
   * Execute a CREATE script for added objects.
   */
  def create(): this.type = {
    resetMsgs()
    _create()
  }
  def _create(): this.type = auto(tx.connection){conn =>
    // We will commit every successfull statement.
    val autoCommit = conn.getAutoCommit
    conn.setAutoCommit(true)
    // Execute a script.
    if (dialect.supportsSchema_?) createObjects(schemata, conn)
    createObjects(preAuxs, conn)
    createObjects(tables, conn)
    createObjects(constraints, conn)
    createObjects(views, conn)
    createObjects(postAuxs, conn)
    // disable Referential integrity check, @todo, it's better to not add RI constraints?
    setReferentialIntegrity(false, conn)
    // Restore auto-commit.
    conn.setAutoCommit(autoCommit)
    return this
  }

  def setReferentialIntegrity(enable: Boolean, conn: Connection) {
    val sql = dialect.setReferentialIntegrity(enable)
    autoClose(conn.prepareStatement(sql)){st =>
      st.executeUpdate
      _msgs ++= List(InfoMsg("OK: ", sql))
    }(e =>
      _msgs ++= List(ErrorMsg(e.getMessage, sql)))
  }
  
  /**
   * Execute a DROP script and then a CREATE script.
   */
  def dropCreate(): this.type = {
    resetMsgs()
    _drop()
    _create()
  }

  override def toString: String = {
    var result = "Circumflex DDL Unit: "
    if (messages.size == 0) {
      val objectsCount = (schemata.size +
                          tables.size +
                          constraints.size +
                          views.size +
                          preAuxs.size +
                          postAuxs.size)
      result += objectsCount + " objects in queue."
    } else {
      val errorsCount = messages.filter(m => m.isInstanceOf[DDLUnit.ErrorMsg]).size
      val infoCount = messages.filter(m => m.isInstanceOf[DDLUnit.InfoMsg]).size
      result += infoCount + " successful statements, " + errorsCount + " errors."
    }
    result
  }
}

// ### Messages

object DDLUnit {
  trait Msg {
    def body: String
    def sql: String
  }
  case class InfoMsg(val body: String, val sql: String) extends Msg
  case class ErrorMsg(val body: String, val sql: String) extends Msg
}