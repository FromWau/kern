package com.fromwau.kern.result

import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The README's snippets, kept here so they compile against the real API and do what the README says. What they call
// but do not declare comes last in the class.
class ReadmeSnippetsTest {

    sealed interface CrudError : ResultError {
        data class NotFound(val id: Long) : CrudError
        data class Invalid(val reason: String) : CrudError
        data class DbError(val cause: Throwable) : CrudError
    }

    sealed interface PaymentError : ResultError {
        override val errorType: String get() = "Payment.${this::class.simpleName}"

        data object Declined : PaymentError
    }

    // The README's literal type, which replaces the case above there; nested so both can be declared at once.
    object Literal {
        data object Declined : PaymentError {
            override val errorType: String get() = "Payment.Declined"
        }
    }

    fun findUserById(id: Long): Result<User, CrudError> {
        if (id <= 0) return Err(CrudError.Invalid("id must be positive"))
        val user = catching({ CrudError.DbError(it) }) { dao.findById(id) }.getOrElse { return Err(it) }
        return user.toResult { CrudError.NotFound(id) }
    }

    fun register(form: Form): Result<User, SignupError> = result {
        if (form.age < 18) Err(SignupError.TooYoung).bind()       // end the block with an error of your own
        val email = validateEmail(form.email).bind { SignupError.Email(it) }   // convert another error type
        val user = createUser(email).bind()
        sendWelcome(user).bind()
        user
    }

    fun emailOf(form: Form): Result<String, SignupError> {
        val email = validateEmail(form.email).getOrElse { return Err(SignupError.Email(it)) }
        return Ok(email)
    }

    fun validate(form: WatchlistForm): Result<Watchlist, AllErrors<FieldError>> = resultAll {
        val name by checkName(form.name).collect()
        val symbols = form.symbols.map { checkSymbol(it).collect() }   // a list, item by item
        val min by checkSeconds(form.min).collect()
        val max by checkSeconds(form.max).collect()
        if (min > max) Err(FieldError.Range).collect()                 // a rule across fields goes last
        Watchlist(name, symbols.map { it.value }, min..max)
    }

    object Wire {
        @Serializable
        sealed interface CrudError : ResultError {
            @Serializable @SerialName("not_found") data class NotFound(val id: Long) : CrudError
        }

        @Serializable
        data class Response(val user: Result<User, CrudError>)

        // Not in the README: a user that encodes as its name, as the README's JSON shows.
        @Serializable
        @JvmInline
        value class User(val name: String)
    }

    @Test
    fun `the overview produces chains collects partitions and catches`() {
        val id = 3L
        val name = "grace"

        fun find(id: Long): Result<User, CrudError> = Err(CrudError.NotFound(id))
        assertEquals("unknown", find(id).map { it.name }.getOrElse { "unknown" })
        val chained: Result<User, CrudError> = result { save(find(id).bind().copy(name = name)).bind() }
        assertEquals(Err(CrudError.NotFound(id)), chained)
        val both: Result<Pair<Int, String>, AllErrors<Bad>> =
            resultAll { val a by checkA(raw).collect(); val b by checkB(raw).collect(); Pair(a, b) }
        assertEquals(Err(AllErrors(listOf(Bad("a"), Bad("b")))), both)
        val (good, bad) = lines.map(::parse).partition()
        assertEquals(listOf(1, 3), good)
        assertEquals(listOf(Bad("x")), bad)
        assertEquals(Ok(null), catching({ CrudError.DbError(it) }) { dao.findById(id) })
    }

    @Test
    fun `an error type is the case's simple name unless the hierarchy namespaces it or gives a literal`() {
        assertEquals("NotFound", CrudError.NotFound(3).errorType)
        assertEquals("Payment.Declined", PaymentError.Declined.errorType)
        assertEquals("Payment.Declined", Literal.Declined.errorType)
    }

    @Test
    fun `finding a user by id is Invalid NotFound or DbError or the user`() {
        assertEquals(Err(CrudError.Invalid("id must be positive")), findUserById(0))
        assertEquals(Err(CrudError.NotFound(2)), findUserById(2))
        assertEquals(Err(CrudError.DbError(dbDown)), findUserById(DB_DOWN_ID))
        assertEquals(Ok(ada), findUserById(1))
    }

    @Test
    fun `a when renders a success and reports an error`() {
        for (id in listOf(1L, 2L)) {
            when (val result = findUserById(id)) {
                is Result.Success -> render(result.value)
                is Result.Error -> report(result.error)
            }
        }

        assertEquals(listOf<Any>(ada, CrudError.NotFound(2)), shown)
    }

    @Test
    fun `map transforms a value flatMap chains a step and map of a step nests the result`() {
        val id = 1L

        val name: Result<String, CrudError> = findUserById(id).map { it.name }        // Result<String, CrudError>
        val saved: Result<User, CrudError> = findUserById(id).flatMap { save(it) }   // Result<User, CrudError>
        val nested: Result<Result<User, CrudError>, CrudError> = findUserById(id).map { save(it) }

        assertEquals(Ok("ada"), name)
        assertEquals(Ok(ada), saved)
        assertEquals(Ok(Ok(ada)), nested)
    }

    @Test
    fun `mapError converts each case into another hierarchy`() {
        val id = 2L

        val shown: Result<User, ApiError> = findUserById(id).mapError { crud ->
            when (crud) {
                is CrudError.NotFound -> ApiError.Missing("no user ${crud.id}")
                is CrudError.Invalid -> ApiError.Missing(crud.reason)
                is CrudError.DbError -> ApiError.Unavailable
            }
        }

        assertEquals(Err(ApiError.Missing("no user 2")), shown)
    }

    @Test
    fun `registering ends at the first failing step with its error converted`() {
        assertEquals(Err(SignupError.TooYoung), register(Form(age = 17, email = "kid@example.com")))
        assertEquals(Err(SignupError.Email(EmailError.NoAt)), register(Form(age = 30, email = "nowhere")))
        assertEquals(Ok(User(2, "ada@example.com")), register(Form(age = 30, email = "ada@example.com")))
        assertEquals(listOf(User(2, "ada@example.com")), welcomed)
    }

    @Test
    fun `getOrElse returns a converted error without a block`() {
        assertEquals(Err(SignupError.Email(EmailError.NoAt)), emailOf(Form(age = 30, email = "nowhere")))
        assertEquals(Ok("ada@example.com"), emailOf(Form(age = 30, email = "ada@example.com")))
    }

    @Test
    fun `several results combine stop partition keep what was done or report every error`() {
        val ports = listOf("80", "443").mapResult(::parsePort)                         // Result<List<Int>, PortError>
        val entries = lines.withIndex().mapResultAll { (i, line) ->                    // every bad line, numbered
            parseLine(line).mapError { RowError(i + 1, it) }
        }
        val (orders, rejected) = lines.map(::parseLine).partition()
        val saved = orders.mapResultPartial { save(it) }
        val config = zipAll(checkPort(raw.port), checkHost(raw.host)) { port, host -> Config(port, host) }

        assertEquals(Ok(listOf(80, 443)), ports)
        assertEquals(Err(AllErrors(listOf(RowError(2, Bad("x"))))), entries)
        assertEquals(listOf(Order(1), Order(3)), orders)
        assertEquals(listOf(Bad("x")), rejected)
        assertEquals(Err(Partial(listOf(Order(1)), Bad("full"))), saved)
        assertEquals(Err(AllErrors(listOf(Bad("port"), Bad("host")))), config)
    }

    @Test
    fun `a resultAll block reports every bad field in order`() {
        assertEquals(
            Err(AllErrors(listOf(FieldError.BadName(""), FieldError.BadSymbol("??")))),
            validate(WatchlistForm(name = "", symbols = listOf("AAPL", "??"), min = 5, max = 60)),
        )
        assertEquals(
            Ok(Watchlist("tech", listOf("AAPL"), 5..60)),
            validate(WatchlistForm(name = "tech", symbols = listOf("AAPL"), min = 5, max = 60)),
        )
        assertEquals(
            Err(AllErrors(listOf(FieldError.Range))),
            validate(WatchlistForm(name = "tech", symbols = listOf("AAPL"), min = 60, max = 5)),
        )
    }

    @Test
    fun `a result encodes as the README's JSON shows`() {
        assertEquals(
            """{"user":{"error":{"type":"not_found","id":7}}}""",
            Json.encodeToString(Wire.Response(Err(Wire.CrudError.NotFound(7)))),
        )
        assertEquals("""{"user":{"success":"ada"}}""", Json.encodeToString(Wire.Response(Ok(Wire.User("ada")))))
    }

    // Not in the README: what the snippets call.

    data class User(val id: Long, val name: String)

    data class Bad(val what: String) : ResultError

    sealed interface ApiError : ResultError {
        data class Missing(val what: String) : ApiError
        data object Unavailable : ApiError
    }

    data class Form(val age: Int, val email: String)

    enum class EmailError : ResultError { NoAt }

    sealed interface SignupError : ResultError {
        data object TooYoung : SignupError
        data class Email(val error: EmailError) : SignupError
    }

    data class WatchlistForm(val name: String, val symbols: List<String>, val min: Int, val max: Int)

    data class Watchlist(val name: String, val symbols: List<String>, val seconds: IntRange)

    sealed interface FieldError : ResultError {
        data class BadName(val name: String) : FieldError
        data class BadSymbol(val symbol: String) : FieldError
        data class BadSeconds(val seconds: Int) : FieldError
        data object Range : FieldError
    }

    data class Raw(val port: String, val host: String)

    data class Config(val port: Int, val host: String)

    data class Order(val quantity: Int)

    data class RowError(val line: Int, val error: Bad) : ResultError

    data class PortError(val given: String) : ResultError

    inner class Dao {
        fun findById(id: Long): User? = when (id) {
            1L -> ada
            DB_DOWN_ID -> throw dbDown
            else -> null
        }
    }

    private val ada = User(1, "ada")
    private val dbDown = IllegalStateException("db down")
    private val dao = Dao()
    private val raw = Raw(port = "", host = "")
    private val lines = listOf("1", "x", "3")
    private val shown = mutableListOf<Any>()
    private val welcomed = mutableListOf<User>()

    private fun render(user: User) {
        shown += user
    }

    private fun report(error: CrudError) {
        shown += error
    }

    private fun save(user: User): Result<User, CrudError> = Ok(user)

    private fun save(order: Order): Result<Order, Bad> = if (order.quantity > 1) Err(Bad("full")) else Ok(order)

    private fun checkA(raw: Raw): Result<Int, Bad> = raw.port.toIntOrNull()?.let { Ok(it) } ?: Err(Bad("a"))

    private fun checkB(raw: Raw): Result<String, Bad> = if (raw.host.isBlank()) Err(Bad("b")) else Ok(raw.host)

    private fun checkPort(port: String): Result<Int, Bad> = port.toIntOrNull()?.let { Ok(it) } ?: Err(Bad("port"))

    private fun checkHost(host: String): Result<String, Bad> = if (host.isBlank()) Err(Bad("host")) else Ok(host)

    private fun parse(line: String): Result<Int, Bad> = line.toIntOrNull()?.let { Ok(it) } ?: Err(Bad(line))

    private fun parseLine(line: String): Result<Order, Bad> = parse(line).map(::Order)

    private fun parsePort(given: String): Result<Int, PortError> =
        given.toIntOrNull()?.let { Ok(it) } ?: Err(PortError(given))

    private fun validateEmail(email: String): Result<String, EmailError> =
        if ('@' in email) Ok(email) else Err(EmailError.NoAt)

    private fun createUser(email: String): Result<User, SignupError> = Ok(User(2, email))

    private fun sendWelcome(user: User): Result<Unit, SignupError> {
        welcomed += user
        return Ok(Unit)
    }

    private fun checkName(name: String): Result<String, FieldError> =
        if (name.isBlank()) Err(FieldError.BadName(name)) else Ok(name)

    private fun checkSymbol(symbol: String): Result<String, FieldError> =
        if (symbol.all { it.isLetter() }) Ok(symbol) else Err(FieldError.BadSymbol(symbol))

    private fun checkSeconds(seconds: Int): Result<Int, FieldError> =
        if (seconds >= 0) Ok(seconds) else Err(FieldError.BadSeconds(seconds))

    private companion object {
        const val DB_DOWN_ID = 13L
    }
}
