package models

import play.api.mvc.Call
import play.api.templates.Html

import controllers._

object NavSection {
  object homeNav extends NavSections(Seq())
  
  val maxNameDisplay = 25
    
  def truncateName(name:String) = {
    if (name.length < maxNameDisplay)
      name
    else {
      val cutoff = Math.max(name.lastIndexOf(" ", maxNameDisplay), 10)
      (name take cutoff) + "..."
    }
  }
  
  def loginNav(rc:RequestContext) = {
    rc.requester map { user =>
      NavSection("Logged in as " + user.name, Seq(
        NavLink("Your Spaces", routes.Application.spaces),
        NavLink("Log out", routes.Application.logout)
      ))
    } getOrElse {
      NavSection("Not logged in", Seq(
        NavLink("Log in", routes.Application.login)
      ))
    }    
  }
      
  def nav(rc:RequestContext) = {
    def spaceId = rc.state.get.toThingId
    val owner = rc.ownerName
    
    val spaceSection = rc.state map { state =>
      NavSection(truncateName(state.displayName), Seq(
        NavLink("Space Home", routes.Application.thing(owner, spaceId, spaceId)),
        NavLink("Create a Thing", routes.Application.createThing(owner, spaceId, None), Some("createThing")),
        NavLink("Add a Property", routes.Application.createProperty(owner, spaceId)),
        NavLink("Upload a Photo", routes.Application.upload(owner, spaceId)),
        NavLink("All Things", routes.Application.thing(owner, spaceId, "All+Things"))
      ))
    }
    
    val thingSection = rc.thing map { thing =>
      val thingId = thing.toThingId
      def attachment:Option[NavLink] = {
        thing.kind match {
          case Kind.Attachment => Some(NavLink("Download", routes.Application.attachment(owner, spaceId, thingId)))
          case _ => None
        }
      }
      NavSection(truncateName(thing.displayName), Seq(
        NavLink("Edit", routes.Application.editThing(owner, spaceId, thingId)),
        NavLink("Create a " + thing.displayName, routes.Application.createThing(owner, spaceId, Some(thingId))),
        NavLink("Export", routes.Application.exportThing(owner, spaceId, thingId))
      ) ++ attachment)
    }
    
    val loginSection = rc.requester map { user =>
      NavSection("Logged in as " + user.name, Seq(
        NavLink("Your Spaces", routes.Application.spaces),
        NavLink("Log out", routes.Application.logout)
      ))
    } getOrElse {
      NavSection("Not logged in", Seq(
        NavLink("Log in", routes.Application.login)
      ))
    }
    
    val sections = Seq(spaceSection, thingSection).flatten
    NavSections(sections)
  }
}

case class NavSections(sections:Seq[NavSection])

case class NavSection(val title:String, val links:Seq[NavLink])

case class NavLink(display:String, url:Call, id:Option[String] = None) {
  def idAttr:Html = Html(id match {
    case Some(i) => " id=\"" + i + "\" "
    case None => ""
  })
}
