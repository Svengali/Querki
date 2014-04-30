package querki.uservalues

import scala.reflect.runtime.universe._
import scala.xml.NodeSeq

import models.{DisplayPropVal, OID, Property, PropertyBundle, PType, PTypeBuilder, Wikitext}

import querki.ecology._
import querki.types.{ModelTypeBase, PropPath}
import querki.util.QLog
import querki.values.{ElemValue, QLContext, QValue, RequestContext, SpaceState}

case class DiscreteSummary[UVT](content:Map[UVT,Int])

/**
 * Describes a mechanism for summarizing the User Values for a Property.
 */
trait Summarizer[UVT,VT] {
  /**
   * Based on the previous and current User Values for a single User, produce an updated summary
   * value for this Thing.
   */
  def addToSummary(tid:OID, fromProp:Property[_,_], prop:Property[VT,_], previous:Option[QValue], current:Option[QValue])(implicit state:SpaceState):QValue
}

trait SummarizerDefs { self:QuerkiEcot =>
  lazy val PropPaths = interface[querki.types.PropPaths]
  lazy val UserValues = interface[UserValues]
  
  lazy val SummarizesPropertyLink = UserValues.SummarizesPropertyLink
  
  /**
   * Base implementation for all Summarizers.
   * 
   * Note that this does *not* derive from SystemType, mainly because I want there to be a common Model
   * for Summarizers.
   */
  abstract class SummarizerBase[UVT,VT](tid:OID, pf:PropFetcher)(implicit e:Ecology) 
    extends PType[VT](tid, SystemIds.systemOID, MOIDs.SummarizerBaseOID, pf)(e) with Summarizer[UVT,VT]
  {
    // HACK: so far, I haven't come up with a compile-time way to deal with this, so we need to do the typechecking at runtime.
    // The problem is that fromProp *might* be of UVT, but it might also be a ModelType.
    // TODO: this really ought to be typechecking that elem *is* a UVT, but erasure makes that hard. How do we do this? So far,
    // my attempts with TypeTags haven't worked.
    def castToUVT(vOpt:Option[QValue]):Option[UVT] = {
      try {
	      for {
	        v <- vOpt
	        first <- v.firstOpt
	        elemV = first.elem
	      }
	        yield elemV.asInstanceOf[UVT]
      } catch {
        // TODO: this needs to become *much* better:
        case ex:Exception => None
      }
    }
    
    def rightPath(paths:Seq[PropPath[_,_]], bundle:PropertyBundle)(implicit state:SpaceState):Option[PropPath[_,_]] = {
      paths.find(path => !(path.getPropOpt(bundle).isEmpty))
    }
    
    def viaPath(bundleOpt:Option[PropertyBundle], path:PropPath[_,_])(implicit state:SpaceState):Option[UVT] = {
      for {
        bundle <- bundleOpt
        pv <- path.getPropOpt(bundle).headOption
        v = pv.v
        result <- castToUVT(Some(v))
      }
        yield result
    }
    
    def addToSummary(tid:OID, fromProp:Property[_,_], prop:Property[VT,_], previous:Option[QValue], current:Option[QValue])(implicit state:SpaceState):QValue = {
      val resultViaPath = fromProp.pType match {
        case mt:ModelTypeBase => {
	      for {
	        fromBundleProp <- fromProp.confirmType(mt)
	        summarizesPropPV <- prop.getPropOpt(SummarizesPropertyLink)
	        summarizesPropId <- summarizesPropPV.firstOpt
	        summarizesProp <- state.prop(summarizesPropId)
	        previousBundle = previous.flatMap(_.firstAs(mt))
	        currentBundle = current.flatMap(_.firstAs(mt))
	        checkBundle <- previousBundle orElse currentBundle 
	        path <- rightPath(PropPaths.pathsToProperty(summarizesProp), checkBundle)
	      }
	        yield ExactlyOne(ElemValue(doAddToSummary(tid, prop, viaPath(previousBundle, path), viaPath(currentBundle, path)), this))
        }
        case _ => None
      }
      
      resultViaPath.getOrElse(ExactlyOne(ElemValue(doAddToSummary(tid, prop, castToUVT(previous), castToUVT(current)), this)))
    }
    def doAddToSummary(tid:OID, prop:Property[VT,_], previous:Option[UVT], current:Option[UVT])(implicit state:SpaceState):VT
  }
  
  /**
   * This Summarizer is intended for any Type with a limited number of discrete values. The Summary is one
   * integer for each value, giving the number of times it has been specified.
   * 
   * The userType is the PType we are actually summarizing values of. We mostly need it for serialization.
   */
  class DiscreteSummarizer[UVT](tid:OID, userType:PType[UVT], p:PropFetcher) extends SummarizerBase[UVT,DiscreteSummary[UVT]](tid, p) {
	def doAddToSummary(tid:OID, prop:Property[DiscreteSummary[UVT],_], previous:Option[UVT], current:Option[UVT])(implicit state:SpaceState):DiscreteSummary[UVT] = {
	  state.anything(tid) match {
	    case Some(thing) => {
	      thing.getPropOpt(prop) match {
		    case Some(oldSummary) => {
		      // TBD: the "first" below is suspicious. Can it ever be wrong?
		      val oldMap = oldSummary.first.content
		      // First, decrement the previous key, if there was one...
		      val mapWithoutPrevious = previous match {
		        case Some(prevKey) if (oldMap.contains(prevKey)) => {
		          val prevVal = (oldMap(prevKey) - 1)
		          if (prevVal == 0)
		            oldMap - prevKey
		          else
		            oldMap + (prevKey -> prevVal) 
		        }
		        case _ => oldMap
		      }
		      // ... then increment the new key, if there is one:
		      val mapWithCurrent = current match {
		        case Some(curKey) => {
		          if (mapWithoutPrevious.contains(curKey))
		            // We already have records with that key:
		            mapWithoutPrevious + (curKey -> (mapWithoutPrevious(curKey) + 1))
		          else
		            // First time someone's given this value:
		            mapWithoutPrevious + (curKey -> 1)
		        }
		        // We've simply removed the previous value, without giving a new one:
		        case _ => mapWithoutPrevious
		      }
		      DiscreteSummary(mapWithCurrent)
		    }
		      
		    case None => {
		      current match {
		        // First value for this Thing:
		        case Some(curVal) => DiscreteSummary(Map(curVal -> 1))
		        // TODO: should this be a warning? Kind of strange to get a "change" that contains no value, if
		        // there wasn't one before. What's the change?
		        case None => DiscreteSummary(Map())
		      }
		    }
		  }        
	    }
	      
	    case None => {
	      QLog.error(s"Got addToSummary for unknown Thing $tid")
	      DiscreteSummary(Map())
	    }
	  }
	}
	  
	def doDeserialize(ser:String)(implicit state:SpaceState):DiscreteSummary[UVT] = {
	  // TODO: this should unescape "," and ":", so we can cope with arbitrary Strings:
	  val pairStrs = ser.split(",")
	  // Filter out the empty-string case:
	  val pairs = if (pairStrs.length > 0 && pairStrs(0).length() > 0) pairStrs.map { pairStr =>
	    val parts = pairStr.split(":")
	    if (parts.length != 2)
	        throw new Exception(s"DiscreteSummarizer.doDeserialize got a bad pair: $pairStr")
	      
	    (userType.doDeserialize(parts(0)), java.lang.Integer.parseInt(parts(1)))
	  } else
	    Array.empty[(UVT,Int)]
	    
	  DiscreteSummary(Map(pairs:_*))
	}
	  
	def doSerialize(summary:DiscreteSummary[UVT])(implicit state:SpaceState):String = {
	  val pairStrs = summary.content.map { pair =>
	    val (key, num) = pair
	    userType.doSerialize(key) + ":" + num.toString
	  }
	  pairStrs.mkString(",")
	}
	  
	def doWikify(context:QLContext)(v:DiscreteSummary[UVT], displayOpt:Option[Wikitext] = None):Wikitext = {
	  // TODO: this should become a fancy histogram. But for now, keep it simple:
	  // TODO: to do this properly, including zero values, we need to know the range of
	  // userType, so that we can iterate over it!
	  (Wikitext.empty /: v.content) { (curText, pair) =>
	    val (key, num) = pair
	    curText + Wikitext(": ") + userType.doWikify(context)(key) + Wikitext(" : " + num.toString + "\n")
	  }
	}
	  
	def doDefault(implicit state:SpaceState):DiscreteSummary[UVT] = DiscreteSummary(Map())
	
	def renderInputXml(prop:Property[_,_], rc:RequestContext, currentValue:DisplayPropVal, v:ElemValue):NodeSeq =
	  <p><i>This will be calculated; you don't input it directly.</i></p>
  }  
}
