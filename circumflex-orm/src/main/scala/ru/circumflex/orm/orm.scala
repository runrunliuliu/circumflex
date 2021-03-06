package ru.circumflex.orm

import java.sql.{Timestamp, PreparedStatement, ResultSet, Connection, SQLException}
import java.util.Date
import java.util.logging.Logger
import javax.naming.InitialContext
import javax.sql.DataSource
import org.aiotrade.lib.util.config.Config
import org.apache.tomcat.jdbc.pool.PoolProperties

// ## Configuration

/**
 * `ORM` singleton aggregates all ORM-related interfaces into a single
 * configuration object.
 */
object ORM {

  val config = Config()

  // ### Global Configuration Objects

  /**
   * Connection provider.
   * Can be overriden with `orm.connectionProvider` configuration parameter.
   */
  val connectionProvider: ConnectionProvider = config.getString("orm.connectionProvider") match {
    case Some(s: String) => Config.loadClass[ConnectionProvider](s).newInstance
    case _ => DefaultConnectionProvider
  }

  /**
   * SQL dialect.
   * Can be overriden with `orm.dialect` configuration parameter.
   */
  val dialect: Dialect = config.getString("orm.dialect") match {
    case Some(s: String) => Config.loadClass[Dialect](s).newInstance
    case _ => DefaultDialect
  }

  /**
   * SQL type converter.
   * Can be overriden with `orm.typeConverter` configuration parameter.
   */
  val typeConverter: TypeConverter = config.getString("orm.typeConverter") match {
    case Some(s: String) => Config.loadClass[TypeConverter](s).newInstance
    case _ => DefaultTypeConverter
  }

  /**
   * The schema name which is used if not specified explicitly.
   * Can be overriden with `orm.defaultSchema` configuration parameter.
   */
  val defaultSchema = config.getString("orm.defaultSchema") match {
    case Some(s: String) => new Schema(s)
    case _ => new Schema("public")
  }

  /**
   * Transaction manager.
   * Can be overriden with `orm.transactionManager` configuration parameter.
   */
  val transactionManager: TransactionManager = config.getString("orm.transactionManager") match {
    case Some(s: String) => Config.loadClass[TransactionManager](s).newInstance
    case _ => DefaultTransactionManager
  }

  /**
   * Thread local to hold temporary aliases.
   *
   * This is necessary to propagate an alias from `RelationNode` to `Field` with
   * the help of implicits.
   */
  private val _lastAlias = new ThreadLocal[String]

  def lastAlias: Option[String] =
    if (_lastAlias.get == null) None
  else {
    val a = _lastAlias.get
    _lastAlias.set(null)
    Some(a)
  }
  def lastAlias(alias: String): Unit = _lastAlias.set(alias)

  val avroDir = config.getString("orm.avro.dir", ".")
}

// ### Connection provider

/**
 * *Connection provider* is used to acquire JDBC connections throughout the application.
 */
trait ConnectionProvider {

  /**
   * Open new JDBC connection.
   */
  def openConnection: Connection
}

/**
 * Default `ConnectionProvider` implementation. It behaves as follows:
 *
 *  * if `orm.connection.datasource` is set, use it to acquire data source
 *  from JNDI;
 *  * if `orm.connection.datasource` is missing, construct a connection
 *  pool using and following configuration parameters:
 *     * `orm.connection.driver`
 *     * `orm.connection.url`
 *     * `orm.connection.username`
 *     * `orm.connection.password`
 *   * set *auto-commit* for each connection to `false`
 *   * set the transaction isolation level to the value `orm.connection.isolation`
 *   (or use `READ COMMITTED` by default)
 *
 * If c3p0 data source is used you can fine tune it's configuration with `c3p0.properties`
 * file (see [c3p0 documentation][c3p0-cfg] for more details).
 *
 * Though `DefaultConnectionProvider` is an optimal choice for most applications, you
 * can create your own connection provider by implementing the `ConnectionProvider` trait
 * and setting the `orm.connectionProvider` configuration parameter.
 *
 *   [c3p0]: http://www.mchange.com/projects/c3p0
 *   [c3p0-cfg]: http://www.mchange.com/projects/c3p0/index.html#configuration_properties
 */
object DefaultConnectionProvider extends ConnectionProvider {
  import ORM._
  private val log = Logger.getLogger(getClass.getName)
  
  private val autocommit: Boolean = config.getBool("orm.connection.autocommit", false)

  private val isolation: Int = config.getString("orm.connection.isolation") match {
    case Some("none") => Connection.TRANSACTION_NONE
    case Some("read_uncommitted") => Connection.TRANSACTION_READ_UNCOMMITTED
    case Some("read_committed") => Connection.TRANSACTION_READ_COMMITTED
    case Some("repeatable_read") => Connection.TRANSACTION_REPEATABLE_READ
    case Some("serializable") => Connection.TRANSACTION_SERIALIZABLE
    case _ => 
      log.info("Using READ COMMITTED isolation, override 'orm.connection.isolation' if necesssary.")
      Connection.TRANSACTION_READ_COMMITTED
  }

  /**
   * Configure datasource instance. It is retrieved from JNDI if 'orm.connection.datasource'
   * is specified or is constructed using c3p0 otherwise.
   */
  protected val ds: DataSource = config.getString("orm.connection.datasource") match {
    case Some(jndiName) => 
      val ctx = new InitialContext
      val ds = ctx.lookup(jndiName).asInstanceOf[DataSource]
      log.info("Using JNDI datasource: " + jndiName)
      ds
    case _ => 
      log.info("Using connection pooling.")
      
      val driver = config.getString("orm.connection.driver") getOrElse {throw ORMException("Missing mandatory configuration parameter 'orm.connection.driver'.")}
      val url = config.getString("orm.connection.url") getOrElse {throw ORMException("Missing mandatory configuration parameter 'orm.connection.url'.")}
      val username = config.getString("orm.connection.username") getOrElse {throw ORMException("Missing mandatory configuration parameter 'orm.connection.username'.")}
      val password = config.getString("orm.connection.password") getOrElse {throw ORMException("Missing mandatory configuration parameter 'orm.connection.password'.")}
      
      val p = new PoolProperties()
      // --- connection config
      p.setDriverClassName(driver)
      p.setUrl(url)
      p.setUsername(username)
      p.setPassword(password)

      // --- pool size config
      p.setInitialSize(config.getInt("orm.connection.initialPoolSize", 4))
      p.setMinIdle(config.getInt("orm.connection.minPoolSize", 4))
      p.setMaxActive(config.getInt("orm.connection.maxPoolSize", 100))

      // --- timeout config
      p.setTimeBetweenEvictionRunsMillis(config.getInt("orm.connection.maxIdleTime", 3000) * 1000) // default 50 mins. After which an idle connection is removed from the pool.
      p.setRemoveAbandoned(true)
      p.setRemoveAbandonedTimeout(config.getInt("orm.connection.abandonedTimeout", 6000)) // 100 mins, Timeout in seconds before an abandoned(in use) connection can be removed
      p.setMinEvictableIdleTimeMillis(60000)

      // --- verifying config
      p.setValidationQuery(config.getString("orm.connection.testQuery", "SELECT 1"))
      p.setTestOnBorrow(config.getBool("orm.connection.testOnBorrow", true))
      p.setTestOnReturn(config.getBool("orm.connection.testOnReturn", false))
      p.setTestWhileIdle(config.getBool("orm.connection.testWhileIdle", true))
      p.setValidationInterval(config.getInt("orm.connection.testPeriod", 30) * 1000) // milliseconds, default 30 seconds, must be less than maxIdleTime

      p.setLogAbandoned(false)
      
      val ds = new org.apache.tomcat.jdbc.pool.DataSource()
      ds.setPoolProperties(p)
      ds
      
// ----- c3p0 configuration
//      val ds = new ComboPooledDataSource()
//      // --- connection config
//      ds.setDriverClass(driver)
//      ds.setJdbcUrl(url)
//      ds.setUser(username)
//      ds.setPassword(password)
//
//      // --- pool size config
//      ds.setInitialPoolSize(config.getInt("orm.connection.initialPoolSize", 4))
//      ds.setMinPoolSize(config.getInt("orm.connection.minPoolSize", 4))
//      ds.setMaxPoolSize(config.getInt("orm.connection.maxPoolSize", 100))
//      ds.setAcquireIncrement(config.getInt("orm.connection.acquireIncrement", 4))
//
//      // --- timeout config
//      ds.setMaxConnectionAge(config.getInt("orm.connection.maxConnectionAge", 7200)) // default 2 hours
//      ds.setMaxIdleTime(config.getInt("orm.connection.maxIdleTime", 3000)) // default 50 mins. After which an idle connection is removed from the pool.
//      ds.setIdleConnectionTestPeriod(config.getInt("orm.connection.connectionTestPeriod", 600)) // default 10 mins, must less than maxIdleTime
//        
//      // --- verifying config
//      ds.setPreferredTestQuery(config.getString("orm.connection.preferredTestQuery", "SELECT 1"))
//      ds.setTestConnectionOnCheckin(config.getBool("orm.connection.testConnectionOnCheckin", true))
//      ds.setTestConnectionOnCheckout(config.getBool("orm.connection.testConnectionOnCheckout", false))
//        
//      ds
  }

  def dataSource: DataSource = ds

  /**
   * Open a new JDBC connection.
   */
  @throws(classOf[SQLException])
  def openConnection: Connection = {
    val conn = dataSource.getConnection
    conn.setAutoCommit(autocommit)
    conn.setTransactionIsolation(isolation)
    conn
  }

}

// ### Type converter

/**
 * *Type converters* are used to read atomic values from JDBC result sets and to set
 * JDBC prepared statement values for execution. If you intend to use custom types,
 * provide your own implementation.
 */
trait TypeConverter {
  import ORM._
  
  /**
   * Read a value from specified `ResultSet` at specified column `alias`.
   */
  def read(rs: ResultSet, alias: String): Any = {
    val result = rs.getObject(alias)
    if (rs.wasNull) return null
    else return result
  }

  /**
   * Write a value to specified `PreparedStatement` at specified `paramIndex`.
   */
  def write(st: PreparedStatement, parameter: Any, paramIndex: Int): Unit = parameter match {
    case Some(p) => write(st, p, paramIndex)
    case None | null => st.setObject(paramIndex, null)
    case value => st.setObject(paramIndex, convert(value))
  }

  /**
   * Convert a value.
   */
  def convert(value: Any): Any = value match {
    case (p: Date) => new Timestamp(p.getTime)
    case value => value
  }

  /**
   * Convert a value to string and return it with SQL-compliant escaping.
   */
  def escape(value: Any): String = convert(value) match {
    case None | null => "null"
    case s: String => dialect.quoteLiteral(s)
    case d: Timestamp => dialect.quoteLiteral(d.toString)
    case other => other.toString
  }
}

object DefaultTypeConverter extends TypeConverter
