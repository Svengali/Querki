package querki

import models.Property

import querki.globals._

/**
 * @author jducoeur
 */
package object apps {
  trait Apps extends EcologyInterface {
    def CanUseAsAppPerm:Property[OID,OID]
    def CanManipulateAppsPerm:Property[OID,OID]
    def ShadowFlag:Property[Boolean, Boolean]
  }
}