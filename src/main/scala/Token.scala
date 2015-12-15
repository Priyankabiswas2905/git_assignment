import java.util.UUID

/**
  * Create unique tokens for authentication.
  */
object Token {

  def newToken(): UUID = UUID.randomUUID()
}
