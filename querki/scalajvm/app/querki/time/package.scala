package querki

import models.{Property, PType, PTypeBuilder, Thing}

import com.github.nscala_time.time._

import querki.ecology._
import querki.globals._
import querki.types.{ModeledPropertyBundle, SimplePropertyBundle}

/**
 * At least for the time being, querki.time is mostly nscala-time with some tweaks.
 * Querki code should usually just import querki.time._.
 */
package object time extends Imports with Implicits {
  object MOIDs extends EcotIds(5) {
    val DateTimeTypeOID = moid(1)
    val ModifiedTimeMethodOID = moid(2)
    val DateTypeOID = moid(3)
    val YearMethodOID = moid(4)
    val MonthMethodOID = moid(5)
    val DayMethodOID = moid(6)
    val TodayFunctionOID = moid(7)
    val PlusDateImplOID = moid(8)
    val CreateTimeFunctionOID = moid(9)
    val InitOnCreateFlagOID = moid(10)
  }
  
  val TimeTag = "Times and Dates"
  
  // The epoch, typically used for "We don't really have a time for this":
  val epoch = new DateTime(0)
  
  trait Time extends EcologyInterface {
    def QDate:PType[DateTime] with PTypeBuilder[DateTime, DateTime]
    def QDateTime:PType[DateTime] with PTypeBuilder[DateTime, DateTime]
  }
  
  implicit class RichDateTime(val dt:DateTime) extends AnyVal {
    def toTimestamp:Common.Timestamp = dt.getMillis
  }
  
  trait QDuration extends EcologyInterface {
    def toPeriod(duration:ModeledPropertyBundle, state:SpaceState):Period
    
    def DurationType:PType[ModeledPropertyBundle] with PTypeBuilder[ModeledPropertyBundle, SimplePropertyBundle]
    
    def DurationKindProp:Property[OID,OID]
    def DurationQuantityProp:Property[Int,Int]
    def DurationProp:Property[ModeledPropertyBundle, SimplePropertyBundle]
    
    def DurationYears:Thing
    def DurationMonths:Thing
    def DurationWeeks:Thing
    def DurationDays:Thing
  }
}
