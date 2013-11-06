package models

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.concurrent.Execution.Implicits._

import play.api._
import play.api.Configuration
import play.api.Play
import play.api.Play.current
import play.api.libs.concurrent._
import play.Configuration

// Database imports:
import anorm.{Success=>AnormSuccess,_}
import play.api.db._
import play.api.Play.current

// nscala-time
import com.github.nscala_time.time.Imports._

import Kind._
import Thing._

import querki.identity.User

import querki.db.ShardKind
import ShardKind._

import system._
import system.OIDs._
import system.SystemSpace._

import SpaceError._

import MIMEType.MIMEType

import querki.evolutions.Evolutions

import modules.time.TimeModule._

import querki.util._

/**
 * The Actor that encapsulates a Space.
 * 
 * As a hard and fast rule, application code in Querki never alters a Space directly.
 * Instead, all changes are described as messages to the Space's Actor; that is
 * responsible for making the actual changes. This way, the database layer is firmly
 * encapsulated, and race conditions are prevented.
 * 
 * Similarly, you fetch the Space's current State from the Space Actor. The State is
 * an immutable object completely describing the Space at a single moment; you are
 * allowed to process that in any way, just not change it.
 * 
 * An Actor's name is based on its OID, as is its Thing Table in the DB. Use Space.sid()
 * to get the name. Note that this has *nothing* to do with the Space's Display Name, which
 * is user-defined. (And unique only to that user.)
 */
class Space extends Actor {
  
  import context._
  import models.system.SystemSpace.{State => systemState, _} 
  
  def id = OID(self.path.name)
  /**
   * TODO: now that I understand Akka better, this is probably better reimplemented as a
   * local parameter of the Receive function. That is, we should start in a Loading
   * state, stashing incoming messages to begin with, and do each DB load (from Spaces
   * and then from the Space table) as its own message, with a DBLoader child Actor
   * driving the load process. Only once we have constructed the SpaceState do we
   * become(ready(state)), and each mutation does a become(ready(newState)). That
   * eliminates this var, and is more true to Akka style. (It also provides us with
   * a clearer approach for dealing with sending out "Loading" indications.) 
   */
  var _currentState:Option[SpaceState] = None
  // This should return okay any time after preStart:
  def state = {
    _currentState match {
      case Some(s) => s
      case None => throw new Exception("State not ready in Actor " + id)
    }
  }
  // TODO: keep the previous states here in the Space, so we can undo/history
  def updateState(newState:SpaceState) = {
    _currentState = Some(newState)
  }
  
  def SpaceSQL(query:String) = Space.SpaceSQL(id, query)
  def AttachSQL(query:String) = Space.AttachSQL(id, query)

  /**
   * This is a var instead of a lazy val, because the name can change at runtime.
   * 
   * TODO: bundle this into the overall state parameter, as described in _currentState.
   * Is there any info here that isn't part of the SpaceState?
   */
  var _currentSpaceInfo:Option[SqlRow] = None
  /**
   * Fetch the high-level information about this Space. Note that this will throw
   * an exception if for some reason we can't load the record. Re-run this if you
   * have reason to believe the Spaces record has been changed. 
   */
  def fetchSpaceInfo() = {
    _currentSpaceInfo = Some(DB.withTransaction(dbName(System)) { implicit conn =>
      SQL("""
          select * from Spaces where id = {id}
          """).on("id" -> id.raw).apply().headOption.get
    })
  }
  def spaceInfo:SqlRow = {
    if (_currentSpaceInfo.isEmpty) fetchSpaceInfo()
    _currentSpaceInfo.get
  }
  def name = spaceInfo.get[String]("name").get
  def owner = OID(spaceInfo.get[Long]("owner").get)
  def version = spaceInfo.get[Int]("version").get
  
  def canRead(who:User, thingId:OID):Boolean = state.canRead(who, thingId)
  
  def canCreate(who:User, modelId:OID):Boolean = state.canCreate(who, modelId)
  
  def canEdit(who:User, thingId:OID):Boolean = state.canEdit(who, thingId)
  
  def changedProperties(oldProps:PropMap, newProps:PropMap):Seq[OID] = {
    val allIds = oldProps.keySet ++ newProps.keySet
    allIds.toSeq.filterNot { propId =>
      val matchesOpt = for (
        oldVal <- oldProps.get(propId);
        newVal <- newProps.get(propId)
          )
        yield oldVal.matches(newVal)
        
      matchesOpt.getOrElse(false)
    }
  }
  
  // This gets pretty convoluted, but we have to check whether the requester is actually allowed to change the specified
  // properties. (At the least, we specifically do *not* allow any Tom, Dick and Harry to change permissions!)
  // TODO: Note that this is not fully implemented in AccessControlModule yet. We'll need to flesh it out further there
  // before we generalize this feature.
  def canChangeProperties(who:User, oldProps:PropMap, newProps:PropMap):Try[Boolean] = {
    val failedProp = changedProperties(oldProps, newProps).find(!state.canChangePropertyValue(who, _))
    failedProp match {
      case Some(propId) => Failure(new Exception("You're not allowed to edit property " + state.anything(propId).get.displayName))
      case None => Success(true)
    }
  }
  
  def loadSpace() = {
    
    // NOTE: this can take a long time! This is the point where we evolve the Space to the
    // current version:
    Evolutions.checkEvolution(id, version)
    
    // TODO: we need to walk up the tree and load any ancestor Apps before we prep this Space
    DB.withTransaction(dbName(ShardKind.User)) { implicit conn =>
      // The stream of all of the Things in this Space:
      val stateStream = SpaceSQL("""
          select * from {tname} where deleted = FALSE
          """)()
      // Split the stream, dividing it by Kind:
      val streamsByKind = stateStream.groupBy(_.get[Int]("kind").get)
      
      // Start off using the App to boot this Space. Then we add each aspect as we read it in.
      // This works decently for now, but will fall afoul when we try to have local meta-Properties;
      // those will wind up with pointer errors.
      // TODO: Do the Property load in multiple phases, so we can have local meta-properties.
      // TODO: this should use the App, not SystemSpace:
      var curState:SpaceState = systemState
      
      // Now load each kind. We do this in order, although in practice it shouldn't
      // matter too much so long as Space comes last:
      def getThingStream[T <: Thing](kind:Int)(builder:(OID, OID, PropMap, DateTime) => T):Stream[T] = {
        streamsByKind.get(kind).getOrElse(Stream.Empty).map({ row =>
          // This is a critical catch, where we log load-time errors. But we don't want to
          // raise them to the user, so objects that fail to load are (for the moment) quietly
          // suppressed.
          // TBD: we should get more refined about these errors, and expose them a bit
          // more -- as it is, errors can propagate widely, so objects just vanish. 
          // But they should generally be considered internal errors.
          try {
            val propMap = Thing.deserializeProps(row.get[String]("props").get, curState)
            val modTime = row.get[DateTime]("modified").get
            Some(
              builder(
                OID(row.get[Long]("id").get), 
                OID(row.get[Long]("model").get), 
                propMap,
                modTime))
          } catch {
            case error:Exception => {
              // TODO: this should go to a more serious error log, that we pay attention to. It
              // indicates an internal DB inconsistency that we should have ways to clean up.
              Logger.error("Error while trying to load ThingStream " + id, error)
              None
            }            
          }
        }).flatten
      }
      
      def getThings[T <: Thing](kind:Int)(builder:(OID, OID, PropMap, DateTime) => T):Map[OID, T] = {
        val tStream = getThingStream(kind)(builder)
        (Map.empty[OID, T] /: tStream) { (m, t) =>
          try {
            m + (t.id -> t)
          } catch {
            case error:Exception => {
              Logger.error("Error while trying to assemble ThingStream " + id, error)
              m
            }
          }
        }
      }
      
      val spaceStream = getThingStream(Kind.Space) { (thingId, modelId, propMap, modTime) =>
        new SpaceState(
             thingId,
             modelId,
             () => propMap,
             owner,
             name,
             modTime,
             Some(systemState),
             // TODO: dynamic PTypes
             Map.empty[OID, PType[_]],
             Map.empty[OID, Property[_,_]],
             Map.empty[OID, ThingState],
             // TODO (probably rather later): dynamic Collections
             Map.empty[OID, Collection]
            )
      }
      
      curState =
        if (spaceStream.isEmpty) {
          // This wants to be a Big Nasty Error!
          Logger.error("Was unable to find/load Space " + id + "/" + name + ". INVESTIGATE THIS!")
          
          // In the meantime, we fall back on a plain Space Thing:
          new SpaceState(
            id,
            systemState.id,
            toProps(
              setName(name),
              DisplayTextProp("We were unable to load " + name + " properly. An error has been logged; our apologies.")
              ),
            owner,
            name,
            modules.time.TimeModule.epoch,
            Some(systemState),
            Map.empty[OID, PType[_]],
            Map.empty[OID, Property[_,_]],
            Map.empty[OID, ThingState],
            // TODO (probably rather later): dynamic Collections
            Map.empty[OID, Collection]
            )
        } else
          spaceStream.head
      
      val loadedProps = getThings(Kind.Property) { (thingId, modelId, propMap, modTime) =>
        val typ = systemState.typ(TypeProp.first(propMap))
        // This cast is slightly weird, but safe and should be necessary. But I'm not sure
        // that the PTypeBuilder part is correct -- we may need to get the RT correct.
//        val boundTyp = typ.asInstanceOf[PType[typ.valType] with PTypeBuilder[typ.valType, Any]]
        val boundTyp = typ.asInstanceOf[PType[Any] with PTypeBuilder[Any, Any]]
        val coll = systemState.coll(CollectionProp.first(propMap))
        // TODO: this feels wrong. coll.implType should be good enough, since it is viewable
        // as Iterable[ElemValue] by definition, but I can't figure out how to make that work.
        val boundColl = coll.asInstanceOf[Collection]
        new Property(thingId, id, modelId, boundTyp, boundColl, () => propMap, modTime)
      }
      curState = curState.copy(spaceProps = loadedProps)
      
      val things = getThings(Kind.Thing) { (thingId, modelId, propMap, modTime) =>
        new ThingState(thingId, id, modelId, () => propMap, modTime)        
      }
      
      val attachments = getThings(Kind.Attachment) { (thingId, modelId, propMap, modTime) =>
        new ThingState(thingId, id, modelId, () => propMap, modTime, Kind.Attachment)        
      }
      
      val allThings = things ++ attachments
      curState = curState.copy(things = allThings)
      
      // Now we do a second pass, to resolve anything left unresolved:
      def secondPassProps[T <: Thing](thing:T)(copier:(T, PropMap) => T):T = {
        val fixedProps = thing.props.map { propPair =>
          val (id, value) = propPair
          value match {
            case unres:UnresolvedPropValue => {
              val propOpt = curState.prop(id)
              val v = propOpt match {
                case Some(prop) => prop.deserialize(value.firstTyped(UnresolvedPropType).get)
                case None => value
              }
              (id, v)
            }
            case _ => propPair
          }
        }
        copier(thing, fixedProps)
      }

      curState = secondPassProps(curState)((state, props) => state.copy(pf = () => props))
      
      val fixedAllProps = curState.spaceProps.map{ propPair =>
        val (id, prop) = propPair
        (id, secondPassProps(prop)((p, metaProps) => p.copy(pf = () => metaProps)))
      }.toSeq
      curState = curState.copy(spaceProps = Map(fixedAllProps:_*))
      
      _currentState = Some(curState)
    }    
  }
  
  override def preStart() = {
    loadSpace()
  } 
  
  def checkSpaceId(thingId:ThingId):OID = {
    thingId match {
      case AsOID(oid) => if (oid == id) oid else throw new Exception("Space " + id + " somehow got message for " + oid)
      case AsName(thingName) => if (NameType.equalNames(thingName, name)) id else throw new Exception("Space " + name + " somehow got message for " + thingName)
    }
  }

  def createSomething(spaceThingId:ThingId, who:User, modelId:OID, props:PropMap, kind:Kind)(
      otherWork:OID => java.sql.Connection => Unit) = 
  {
    val spaceId = checkSpaceId(spaceThingId)
    val name = NameProp.firstOpt(props)
    val canChangeTry = canChangeProperties(who, Map.empty, props)
    if (!canCreate(who, modelId))
      sender ! ThingFailed(CreateNotAllowed, "You are not allowed to create that")
    else if (canChangeTry.isFailure) {
      canChangeTry.recover { case ex:Throwable => sender ! ThingFailed(CreateNotAllowed, ex.getMessage()) }
    } else if (name.isDefined && state.anythingByName(name.get).isDefined)
      sender ! ThingFailed(NameExists, "This Space already has a Thing with that name")
    else DB.withTransaction(dbName(ShardKind.User)) { implicit conn =>
      val thingId = OID.next(ShardKind.User)
      // TODO: add a history record
      Space.createThingInSql(thingId, spaceId, modelId, kind, props, state)
      // TBD: this isn't quite right -- we really should be taking the DB's definition of the timestamp
      // instead:
      val modTime = DateTime.now
      kind match {
        case Kind.Thing | Kind.Attachment => {
          val thing = ThingState(thingId, spaceId, modelId, () => props, modTime, kind)
          updateState(state.copy(things = state.things + (thingId -> thing)))
        }
        case Kind.Property => {
          val typ = state.typ(TypeProp.first(props))
          val coll = state.coll(CollectionProp.first(props))
//          val boundTyp = typ.asInstanceOf[PType[typ.valType] with PTypeBuilder[typ.valType, Any]]
          val boundTyp = typ.asInstanceOf[PType[Any] with PTypeBuilder[Any, Any]]
          val boundColl = coll.asInstanceOf[Collection]
          val thing = Property(thingId, spaceId, modelId, boundTyp, boundColl, () => props, modTime)
          updateState(state.copy(spaceProps = state.spaceProps + (thingId -> thing)))          
        }
        case _ => throw new Exception("Got a request to create a thing of kind " + kind + ", but don't know how yet!")
      }
      // This callback intentionally takes place inside the transaction:
      otherWork(thingId)(conn)
        
      sender ! ThingFound(thingId, state)
    }    
  }
  
  def modifyThing(who:User, owner:OID, spaceThingId:ThingId, thingId:ThingId, modelIdOpt:Option[OID], pf:(Thing => PropMap)) = {
      val spaceId = checkSpaceId(spaceThingId)
      val oldThingOpt = state.anything(thingId)
      oldThingOpt map { oldThing =>
        val thingId = oldThing.id
        val newProps = pf(oldThing)
        val canChangeTry = canChangeProperties(who, oldThing.props, newProps)
        if (!canEdit(who, thingId)) {
          sender ! ThingFailed(ModifyNotAllowed, "You're not allowed to modify that")
        } else if (canChangeTry.isFailure) {
          canChangeTry.recover { case ex:Throwable => sender ! ThingFailed(ModifyNotAllowed, ex.getMessage()) }
        } else {
	      DB.withTransaction(dbName(ShardKind.User)) { implicit conn =>
	        // TODO: compare properties, build a history record of the changes
	        val modelId = modelIdOpt match {
	          case Some(m) => m
	          case None => oldThing.model
	        }
	        Space.SpaceSQL(spaceId, """
	          UPDATE {tname}
	          SET model = {modelId}, props = {props}
	          WHERE id = {thingId}
	          """
	          ).on("thingId" -> thingId.raw,
	               "modelId" -> modelId.raw,
	               "props" -> Thing.serializeProps(newProps, state)).executeUpdate()
	        // TODO: in principle, this should come from the DB's timestamp:
	        val modTime = DateTime.now
	        // TODO: this needs a clause for each Kind you can get:
            oldThing match {
              case t:ThingState => {
	            val newThingState = t.copy(m = modelId, pf = () => newProps, mt = modTime)
	            updateState(state.copy(things = state.things + (thingId -> newThingState))) 
              }
              case prop:Property[_,_] => {
	            val newThingState = prop.copy(m = modelId, pf = () => newProps, mt = modTime)
	            updateState(state.copy(spaceProps = state.spaceProps + (thingId -> newThingState))) 
              }
              case s:SpaceState => {
                // TODO: handle changing the owner or apps of the Space. (Different messages?)
                val rawName = NameProp.first(newProps)
                val newName = NameType.canonicalize(rawName)
                val oldName = NameProp.first(oldThing.props)
                val oldDisplay = DisplayNameProp.firstOpt(oldThing.props) map (_.raw.toString) getOrElse rawName
                val newDisplay = DisplayNameProp.firstOpt(newProps) map (_.raw.toString) getOrElse rawName
                if (!NameType.equalNames(newName, oldName) || !(oldDisplay.contentEquals(newDisplay))) {
                  SQL("""
                    UPDATE Spaces
                    SET name = {newName}, display = {displayName}
                    WHERE id = {thingId}
                    """
                  ).on("newName" -> newName, 
                       "thingId" -> thingId.raw,
                       "displayName" -> newDisplay).executeUpdate()
                }
                updateState(state.copy(m = modelId, pf = () => newProps, name = newName, mt = modTime))
              }
	        }
	        sender ! ThingFound(thingId, state)
	      }
	      // TODO: this seems ridiculously over-conservative. Is there any reason to do this other than
	      // when we get a modify on the SpaceState? It does explain why I'm seeing those extra selects in
	      // the log.
          fetchSpaceInfo()
        }
      } getOrElse {
        sender ! ThingFailed(UnknownPath, "Thing not found")
      }    
  }
  
  def deleteThing(who:User, spaceThingId:ThingId, thingId:ThingId) = {
    val spaceId = checkSpaceId(spaceThingId)
    val oldThingOpt = state.anything(thingId)
    oldThingOpt map { oldThing =>
      // TODO: eventually we will allow you to delete Properties, but we're nowhere near
      // ready to cope with all the potential error conditions yet.
      if (!oldThing.isInstanceOf[ThingState] || !canEdit(who, oldThing.id)) {
        sender ! ThingFailed(ModifyNotAllowed, "You're not allowed to delete that")
      } else {
        val thingId = oldThing.id
        DB.withTransaction(dbName(ShardKind.User)) { implicit conn =>
	      // TODO: add a history record
	      Space.SpaceSQL(spaceId, """
	        UPDATE {tname}
	        SET deleted = TRUE
	        WHERE id = {thingId}
	        """
	      ).on("thingId" -> thingId.raw).executeUpdate()
        }
        updateState(state.copy(things = state.things - thingId)) 
        sender ! ThingFound(thingId, state)
      }
    } getOrElse {
      sender ! ThingFailed(UnknownPath, "Thing not found")
    }
  }
  
  def receive = {
    case req:CreateSpace => {
      sender ! ThingFound(UnknownOID, state)
    }

    case CreateThing(who, owner, spaceId, kind, modelId, props) => {
      createSomething(spaceId, who, modelId, props, kind) { thingId => implicit conn => Unit }
    }
    
    case CreateAttachment(who, owner, spaceId, content, mime, size, modelId, props) => {
      createSomething(spaceId, who, modelId, props, Kind.Attachment) { thingId => implicit conn =>
      	AttachSQL("""
          INSERT INTO {tname}
          (id, mime, size, content) VALUES
          ({thingId}, {mime}, {size}, {content})
        """
        ).on("thingId" -> thingId.raw,
             "mime" -> mime,
             "size" -> size,
             "content" -> content).executeUpdate()       
      }
    }
    
    case GetAttachment(who, owner, space, attachId) => {
        val attachOid = attachId match {
          case AsOID(oid) => oid
          // TODO: handle the case where this name is not recognized:
          case AsName(name) => {
            state.anythingByName(name).get.id
          }
        }
      // NOTE: see note in GetThing below. 
//      if (!canRead(who, attachOid))
//        sender ! AttachmentFailed
//      else 
      DB.withTransaction(dbName(ShardKind.User)) { implicit conn =>
        // TODO: this will throw an error if the specified attachment doesn't exist
        // Guard against that.
        val results = AttachSQL("""
            SELECT mime, size, content FROM {tname} where id = {id}
            """).on("id" -> attachOid.raw)().map {
          // Note: this weird duplication is a historical artifact, due to the fact
          // that some of the attachment tables have "content" as a nullable column,
          // and some don't. We may eventually want to evolve everything into
          // consistency...
          case Row(Some(mime:MIMEType), Some(size:Int), Some(content:Array[Byte])) => {
            AttachmentContents(attachOid, size, mime, content)
          }
          case Row(mime:MIMEType, size:Int, Some(content:Array[Byte])) => {
            AttachmentContents(attachOid, size, mime, content)
          }
          case Row(mime:MIMEType, size:Int, content:Array[Byte]) => {
            AttachmentContents(attachOid, size, mime, content)
          }
        }.head
        sender ! results
      }
    }
    
    case ChangeProps(who, owner, spaceThingId, thingId, changedProps) => {
      modifyThing(who, owner, spaceThingId, thingId, None, (_.props ++ changedProps))
    }
    
    case ModifyThing(who, owner, spaceThingId, thingId, modelId, newProps) => {
      modifyThing(who, owner, spaceThingId, thingId, Some(modelId), (_ => newProps))
    }
    
    case DeleteThing(who, owner, spaceThingId, thingId) => {
      deleteThing(who, spaceThingId, thingId)
    }
    
    case GetThing(req, owner, space, thingIdOpt) => {
      val thingId = thingIdOpt.flatMap(state.anything(_)).map(_.id).getOrElse(UnknownOID)
      // NOTE: Responsibility for this canRead() check has been pulled out to the Application level for now,
      // by necessity. We don't even necessarily know *who* the requester is until Application gets the
      // State back, because a locally-defined Identity requires the State.
//      if (!canRead(req, thingId))
//        sender ! ThingFailed(SpaceNotFound, "Space not found")
//      else 
      if (thingIdOpt.isDefined) {
        val thingOpt = state.anything(thingIdOpt.get)
        if (thingOpt.isDefined) {
          sender ! ThingFound(thingOpt.get.id, state)
        } else {
          thingIdOpt.get match {
            // TODO: this potentially leaks information. It is frequently legal to see the Space if the name is unknown --
            // that is how tags work -- but we should think through how to keep that properly controlled.
            case AsName(name) => sender ! ThingFailed(UnknownName, "No Thing found with that name", Some(state))
            case AsOID(id) => sender ! ThingFailed(UnknownID, "No Thing found with that id")
          }
        }
      } else {
        // TODO: is this the most semantically appropriate response?
        sender ! ThingFound(UnknownOID, state)
      }
    }
  }
}

object Space {
  // The name of the Space Actor
  def sid(id:OID) = id.toString
  // The OID of the Space, based on the sid
  def oid(sid:String) = OID(sid)
  // The name of the Space's Thing Table
  def thingTable(id:OID) = "s" + sid(id)
  // The name of the Space's History Table
  def historyTable(id:OID) = "h" + sid(id)
  // The name of the Space's Attachments Table
  def attachTable(id:OID) = "a" + sid(id)
  // The name of a backup for the Thing Table
  def backupTable(id:OID, version:Int) = thingTable(id) + "_Backup" + version

  /**
   * A Map of Things, suitable for passing into a SpaceState.
   */
  def oidMap[T <: Thing](items:T*):Map[OID,T] = {
    (Map.empty[OID,T] /: items) ((m, i) => m + (i.id -> i))
  }
  
  /**
   * The intent here is to use this with queries that use the thingTable. You can't use
   * on()-style parameters for table names (because on() quotes the params in a way that makes
   * MySQL choke), so we need to work around that.
   * 
   * You can always use this in place of ordinary SQL(); it is simply a no-op for ordinary queries.
   * 
   * If you need to use the {bname} parameter, you must pass in a version number.
   */
  def SpaceSQL(spaceId:OID, query:String, version:Int = 0):SqlQuery = {
    val replQuery = query.replace("{tname}", thingTable(spaceId)).replace("{bname}", backupTable(spaceId, version))
    SQL(replQuery)
  }
  def createThingInSql(thingId:OID, spaceId:OID, modelId:OID, kind:Int, props:PropMap, serialContext:SpaceState)(implicit conn:java.sql.Connection) = {
    SpaceSQL(spaceId, """
        INSERT INTO {tname}
        (id, model, kind, props) VALUES
        ({thingId}, {modelId}, {kind}, {props})
        """
        ).on("thingId" -> thingId.raw,
             "modelId" -> modelId.raw,
             "kind" -> kind,
             "props" -> Thing.serializeProps(props, serialContext)).executeUpdate()    
  }
  
  def AttachSQL(spaceId:OID, query:String):SqlQuery = SQL(query.replace("{tname}", attachTable(spaceId)))
}
