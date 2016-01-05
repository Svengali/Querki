package querki.search

import querki.globals._
import querki.test._

/**
 * @author jducoeur
 */
class SearchTests extends QuerkiTests {
  lazy val Search = interface[Search]
  
  implicit class resultChecker(resOpt:Option[SearchResultsInternal]) {
    def shouldHave(f:SearchResultInternal => Boolean) = {
      resOpt match {
        case Some(res) => {
          val answer = res.results.find(f)
          assert(answer.isDefined, s"Didn't get expected result from search(${res.request})!")
        }
        case _ => fail("Got None from search()!")
      }
    }
    
    def shouldntHave(f:SearchResultInternal => Boolean) = {
      resOpt match {
        case Some(res) => {
          val answer = res.results.find(f)
          assert(answer.isEmpty, s"Found unexpected search result $answer from search(${res.request})!")
        }
        case None => // That's fine in this case
      }
    }
  }
  
  "search()" should {
    "find display names" in {
      class TSpace extends CommonSpace {
        val bluebox = new SimpleTestThing("b2", Basic.DisplayNameProp("Blue Box"))
        val cello = new SimpleTestThing("b3", Basic.DisplayNameProp("Cello"))
      }
      implicit val s = new TSpace
      
      val results = Search.search("box")(s.state)
      results.shouldHave(_.thing == s.sandbox)
      results.shouldHave(_.thing == s.bluebox)
      results.shouldntHave(_.thing == s.cello)
    }
    
    "find text values" in {
      class TSpace extends CommonSpace {
        val t1 = new SimpleTestThing("t1", optTextProp("Now is the winter of our discontent"))
        val t2 = new SimpleTestThing("t2", optTextProp("made glorious summer"))
      }
      implicit val s = new TSpace
      
      val results = Search.search("glorious")(s.state)
      results.shouldHave(_.thing == s.t2)
      results.shouldntHave(_.thing == s.t1)
    }
  }
}
