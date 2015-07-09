package querki

import scala.concurrent.Future
import scala.reflect.ClassTag

import akka.actor.ActorRef

import querki.ecology._
import querki.identity.{Identity, PublicIdentity, User}

package object session {

  /**
   * Allows Actors to invoke Autowire Requests that come to them.
   * 
   * TODO: this should be renamed and moved, now that it is being used outside of just UserSpaceSession.
   */
  trait SessionInvocation extends EcologyInterface {
    def routeRequest[R](req:messages.ClientRequest)(cb: PartialFunction[Any, Future[R]]):Future[R]
    def handleSessionRequest(req:autowire.Core.Request[String], params:AutowireParams):Unit
  }

  /**
   * Provides access to the UserSessions.
   */
  trait Session extends EcologyInterface {
    /**
     * The root of the UserSession hierarchy. Other Actors may use this directly to send messages to UserSessions.
     */
    def sessionManager:ActorRef
    
    /**
     * Asynchronously fetch all of this Identity's Collaborators -- people they share Spaces with --  who fit the given search term.
     */
    def getCollaborators(user:User, identity:Identity, term:String):Future[UserSessionMessages.Collaborators]
  }
  
  /**
   * Post-init-time interface for registering handlers for Session APIs.
   */
  trait SessionHandlerRegistry extends EcologyInterface {
    /**
     * Registers the given IMPL as the implementation for the specified API. Should be called during postInit()!
     */
    def registerUserSessionImplFor[API, IMPL <: API with AutowireApiImpl](router:ActorRef)(implicit apiTag:ClassTag[API], implTag:ClassTag[IMPL])
  }
  
}