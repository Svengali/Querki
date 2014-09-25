package querki.comm
  
import scala.scalajs.js

import org.scalajs.jquery.JQueryAjaxSettings

/**
 * Provides access to the routing table.
 * 
 * All entry points used by the Client (which in practice probably means all of them) should be
 * declared in the clientRoutes table in client.scala.html. Twirl turns that into proper Javascript
 * calls, and exposes them to the global namespace as "clientRoutes". This, in turn, picks those up
 * and provides them to the Client code as needed.
 * 
 * @TODO: this isn't bad, but it's still mediocre -- since it's js.Dynamic, there's no static checking
 * of the calls at all. We'd like to do something that's truly strongly typed, but Autowire isn't the
 * solution to this particular problem, since it's fundamentally Play-based. The right solution is
 * probably at compile time, to build something that gets at the routing information *before* Scala.js,
 * reflects on that, and generates the client-side glue code.
 */
@scala.scalajs.js.annotation.JSName("clientRoutes")
object ClientRoutes extends js.Object {
  def controllers:js.Dynamic = ???
}

/**
 * Represents a single Play entry point. You usually fetch this via your controllers, as in:
 * {{{
 * val call:PlayCall = ClientRoutes.controllers.MyController.MyCall(param1, param2)
 * }}}
 * Note that the type declaration is, sadly, necessary, in order to coerce the js.Dynamic result
 * from the controller into a PlayCall.
 * 
 * Note that this type is dynamically generated by Play, via the javascriptRouter().
 */
trait PlayCall extends js.Object {
  /**
   * Call this entry point with AJAX, using the default settings.
   */
  def ajax():js.Dynamic = ???
  
  /**
   * Call this AJAX entry point with the given jQuery settings.
   */
  def ajax(settings:JQueryAjaxSettings):js.Dynamic = ???
  
  /**
   * The method of this entry point -- "GET", "POST" or whatever. Known in jQuery as "type".
   */
  def method:String = ???
  
  /**
   * Synonym for method.
   */
  def `type`:String = ???
  
  /**
   * The relative URL of this call.
   */
  def url:URL = ???
  
  /**
   * The absolute URL of this call.
   */
  def absoluteURL:URL = ???
}
