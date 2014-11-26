package querki.session

import scala.concurrent.{Future, Promise}

import akka.actor._

import models.{DisplayPropVal, DisplayText, FieldIds, FormFieldInfo, IndexedOID, Kind, Thing, ThingId, Wikitext}
import models.Thing.{emptyProps, PropMap}

import querki.globals._

import querki.api.EditFunctions
import EditFunctions._
import querki.core.QLText
import querki.data.ThingInfo
import querki.session.messages.ChangeProps2
import querki.spaces.messages.{CreateThing, ThingFound, ThingError}
import querki.util.Requester
import querki.values.{QLRequestContext, RequestContext}

trait EditFunctionsImpl extends SessionApiImpl with EditFunctions { myself:Actor with Requester =>
  
  def ClientApi:querki.api.ClientApi
  lazy val Conventions = interface[querki.conventions.Conventions]
  lazy val Core = interface[querki.core.Core]
  lazy val Editor = interface[querki.editing.Editor]
  lazy val HtmlRenderer = interface[querki.html.HtmlRenderer]
  lazy val PropListManager = interface[querki.core.PropListManager]
  def QL:querki.ql.QL
  lazy val Types = interface[querki.types.Types]
  
  lazy val doLogEdits = Config.getBoolean("querki.test.logEdits", false)
  
  def changeToProps(thing:Option[Thing], path:String, vs:List[String]):Option[PropMap] = {
    implicit val s = state
    DisplayPropVal.propPathFromName(path, thing).map { fieldIds =>
      // Compute the *actual* fields to change. Note that this isn't trivial, since the actual change might be in 
	  // a Bundle:
	  val context = QLRequestContext(rc)
	  val actualFormFieldInfo = HtmlRenderer.propValFromUser(fieldIds, vs, context)
	  val result = fieldIds.container match {
	    // If this value is contained inside (potentially nested) Bundles, dive down into them
	    // and adjust the results:
        case Some(higherFieldIds) => {
          // TEMP: restructure rebuildBundle to take higherFieldIds directly:
          def toHigherIds(oneFieldId:FieldIds):List[IndexedOID] = {
            val thisId = IndexedOID(oneFieldId.p.id, oneFieldId.index)
            oneFieldId.container match {
              case Some(c) => toHigherIds(c) ::: List(thisId)
              case None => List(thisId)
            }
          }
          Types.rebuildBundle(thing, toHigherIds(higherFieldIds), actualFormFieldInfo).
            getOrElse(FormFieldInfo(fieldIds.p, None, true, false, None, Some(new PublicException("Didn't get bundle"))))         
        }
        case None => actualFormFieldInfo
	  }
	    
	  val FormFieldInfo(prop, value, _, _, _, _) = result
	  Core.toProps((prop, value.get))()
    }    
  }
  
  // TODO: this doesn't work with Lists that are nested in Models yet! Merge this with the above, but carefully. There
  // are common concepts of finding the Property, then creating the new value, then putting it into the right place.
  // But this may actually generalize to a broad concept of "change", as opposed to "replace".
  def alterListOrder(thing:Thing, path:String, from:Int, to:Int):Option[PropMap] = {
    implicit val s = state
    for {
      fieldIds <- DisplayPropVal.propPathFromName(path, Some(thing))
      prop = fieldIds.p
      pv <- thing.getPropOpt(prop)
      v = pv.v
      list = v.cv.toSeq
      if (list.isDefinedAt(from))
      elem = list(from)
      removed = list.patch(from, List(), 1)
      newList = removed.patch(to, List(elem), 0)
      newV = v.cType.makePropValue(newList, v.pType)
    }
      yield Core.toProps((prop, newV))()
  }
  
  def doChangeProps(thing:Thing, props:PropMap):Future[PropertyChangeResponse] = {
    val promise = Promise[PropertyChangeResponse]

    self.request(createRequest(ChangeProps2(thing.toThingId, props))) {
      case ThingFound(_, _) => promise.success(PropertyChanged)
      
      // TODO: instead of PropertyChangeError, we really should have a generalized exception mechanism
      // at the autowire level, and do a promise.failure() here:
      case ThingError(ex, _) => promise.success(PropertyChangeError(ex.display(Some(rc))))
    }
    
    promise.future
  }
  
  def alterProperty(thingId:String, change:PropertyChange):Future[PropertyChangeResponse] = withThing(thingId) { thing =>
    if (doLogEdits) QLog.spew(s"Got alterProperty on $thingId: $change")
    
    val propsOpt:Option[PropMap] = change match {
      case ChangePropertyValue(path, vs) => changeToProps(Some(thing), path, vs)
      
      case MoveListItem(path, from, to) => alterListOrder(thing, path, from, to)
      
      case AddListItem(path) => {
        implicit val s = state
        for {
	      fieldIds <- DisplayPropVal.propPathFromName(path, Some(thing))
	      prop = fieldIds.p
	      if (prop.cType == Core.QList)
	      pt = prop.pType
	      pv <- thing.getPropOpt(prop)
	      v = pv.v
	      list = v.cv.toSeq
	      newI = list.size
          newElem = pt.default
          newList = list :+ newElem
          newV = v.cType.makePropValue(newList, pt)
        }
          yield Core.toProps((prop, newV))()        
      }
      
      case DeleteListItem(path, index) => {
        implicit val s = state
	    for {
	      fieldIds <- DisplayPropVal.propPathFromName(path, Some(thing))
	      prop = fieldIds.p
	      pv <- thing.getPropOpt(prop)
	      v = pv.v
	      list = v.cv.toSeq
	      if (list.isDefinedAt(index))
	      newList = list.patch(index, List(), 1)
	      newV = v.cType.makePropValue(newList, v.pType)
	    }
	      yield Core.toProps((prop, newV))()        
      }
      
      case DeleteProperty => None  // NYI
    }
    
    propsOpt match {
      case Some(props) => doChangeProps(thing, props)
      case None => Future.successful(PropertyChangeError("Unable to change property!"))
    }
  }
  
  def create(modelId:String, initialProps:Seq[PropertyChange]):Future[ThingInfo] = withThing(modelId) { model =>
    val promise = Promise[ThingInfo]
    
    val props = (emptyProps /: initialProps) { (map, change) =>
      change match {
        case ChangePropertyValue(path, vs) => changeToProps(None, path, vs) match {
          case Some(p) => map ++ p
          case None => throw new Exception(s"Invalid path $path")
        }
        case _ => throw new Exception(s"Invalid change for create $change")
      }
    }
    
    val theRc = rc
    spaceRouter.request(CreateThing(user, state.owner, state.toThingId, Kind.Thing, model.id, props)) {
      case ThingFound(thingId, newState) => {
        newState.anything(thingId) match {
          case Some(thing) => {
	        promise.success(ClientApi.thingInfo(thing, theRc))
          }
          case None => promise.failure(new Exception("INTERNAL ERROR: Space claimed to create a new Thing, but seems to have failed?!?"))
        }
      }
      case ThingError(error, stateOpt) => promise.failure(error)
    }
    
    promise.future
  }
  
  def getThingEditors(thingId:String):FullEditInfo = withThing(thingId) { thing =>
    implicit val s = state
    val model = thing.getModel
    val props = PropListManager.from(thing)
    val propList = PropListManager.prepPropList(props, Some(thing), model, state)
    val context = thing.thisAsContext(rc)
    
    val propInfos = propList.filter(_._1.id != querki.editing.MOIDs.InstanceEditPropsOID).map { entry =>
      val (prop, propVal) = entry
      val rendered = HtmlRenderer.renderPropertyInputStr(context, prop, propVal)
      PropEditInfo(
        prop.id.toThingId,
        prop.displayName,
        propVal.inputControlId,
        prop.getPropOpt(Editor.PromptProp).filter(!_.isEmpty).map(_.renderPlain),
        prop.getPropOpt(Conventions.PropSummary).map(_.render(prop.thisAsContext(rc))),
        propVal.inheritedFrom.map(_.displayName),
        rendered
      )
    }
    
    val instanceProps = for {
      instancePropsPair <- propList.find(_._1.id == querki.editing.MOIDs.InstanceEditPropsOID)
      instancePropsQV <- instancePropsPair._2.v
      instanceProps = instancePropsQV.rawList(Core.LinkType)
    }
      yield instanceProps.map(_.toThingId.toString)
    
    FullEditInfo(instanceProps.getOrElse(Seq.empty), propInfos)
  }
}
