package querki.display.input

import scala.scalajs.js
import js.ThisFunction._
import org.scalajs.dom

import querki.globals._

/**
 * Private interface, allowing InputGadgets to work with their master controller.
 */
private [input] trait InputGadgetsInternal extends EcologyInterface {
    /**
     * Each InputGadget should register itself here, to ensure that it gets hooked.
     */
    def gadgetCreated(gadget:InputGadget[_]):Unit
    
    /**
     * Record that this Gadget has begun to be edited, and is not yet saved. Use this for complex Gadgets
     * that don't simply save immediately on every change, so that we can force-save when needed.
     */
    def startingEdits(gadget:InputGadget[_]):Unit
    
    /**
     * The pair to startingEdits(), which should be called when save is complete.
     */
    def saveComplete(gadget:InputGadget[_]):Unit
}

class InputGadgetsEcot(e:Ecology) extends ClientEcot(e) with InputGadgets with InputGadgetsInternal {
  
  def implements = Set(classOf[InputGadgets], classOf[InputGadgetsInternal])
  
  /**
   * The factory function for an InputGadget. It is consistent and trivial, but we don't have
   * reflection here, so can't just automate it.
   */
  type InputConstr = (dom.Element => InputGadget[_])
  
  /**
   * Register an InputGadget. Whenever the specified hookClass is encountered, the given Gadget
   * will be wrapped around that Element.
   */
  def registerGadget(hookClass:String, constr:InputConstr) = {
    registry += (hookClass -> constr)
  }
  
  /**
   * Register an InputGadget that doesn't require fancy construction. This is usually the right
   * answer when the InputGadget doesn't take constructor parameters.
   */
  def registerSimpleGadget(hookClass:String, constr: => InputGadget[_]) = {
    val fullConstr = { e:dom.Element =>
      val gadget = constr
      gadget.setElem(e)
      gadget
    }
    registerGadget(hookClass, fullConstr)
  }
  
  override def postInit() = {
    registerSimpleGadget("._textEdit", { new TextInputGadget })
    registerSimpleGadget("._largeTextEdit", { new LargeTextInputGadget })
    registerGadget("._tagSetInput", { TagSetInput(_) })
    registerGadget("._tagInput", { MarcoPoloInput(_) })
    // TODO: this ought to start with an underscore:
    registerSimpleGadget(".sortableList", { new SortableListGadget })
    // Note that we currently assume all selects are inputs:
    registerSimpleGadget("select", { new SelectGadget })
    registerGadget("._deleteInstanceButton", { DeleteInstanceButton(_) })
    registerSimpleGadget("._rating", { new RatingGadget })
  }
  
  /**
   * The actual registry of all of the InputGadgets. This is a map from the name of the marker
   * class for this InputGadget to a factory function for it. 
   * 
   * The coupling here is a bit unfortunate, but
   * seems to be the least boilerplatey way I can think of to do things, given that we don't have
   * reflection (and thus, dynamic construction) on the client side.
   * 
   * TODO: these entries should probably become registered factories instead, so they don't all
   * wind up in this central list.
   */
  var registry = Map.empty[String, InputConstr]
  
  var unhookedGadgets = Set.empty[InputGadget[_]]
  
  def gadgetCreated(gadget:InputGadget[_]) =
    unhookedGadgets += gadget
  
  def hookPendingGadgets() = {
    unhookedGadgets.foreach(_.prep())
    unhookedGadgets = Set.empty
  }
  
  def createInputGadgets(root:dom.Element) = {
    registry.foreach { pair =>
      val (className, constr) = pair
      $(root).find(s"$className").each({ (elem:dom.Element) =>
        val gadget = constr(elem)
      }:js.ThisFunction0[dom.Element, Any])
    }
  }
  
  /**
   * Gadgets that are currently being edited, which haven't yet been saved.
   */
  var gadgetsBeingEdited = Set.empty[InputGadget[_]]
  
  var savePromise:Option[Promise[Unit]] = None
  
  def startingEdits(gadget:InputGadget[_]) = {
    gadgetsBeingEdited += gadget
  }
  def saveComplete(gadget:InputGadget[_]) = {
    gadgetsBeingEdited -= gadget
    if (gadgetsBeingEdited.isEmpty && savePromise.isDefined) {
      val promise = savePromise.get
      savePromise = None
      promise.success()
    }
  }
  
  def afterAllSaved:Future[Unit] = {
    if (gadgetsBeingEdited.isEmpty)
      Future.successful()
    else {
      val promise = Promise[Unit]
      savePromise = Some(promise)
      gadgetsBeingEdited.foreach(_.save())
      promise.future
    }
  }
}
