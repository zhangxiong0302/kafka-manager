/**
 * Copyright 2015 Yahoo Inc. Licensed under the Apache License, Version 2.0
 * See accompanying LICENSE file.
 */

package controllers

import java.util.Properties

import kafka.manager.ActorModel.LogkafkaIdentity
import kafka.manager.utils.LogkafkaNewConfigs
import kafka.manager.{Kafka_0_8_2_1, ApiError, Kafka_0_8_2_0, Kafka_0_8_1_1}
import models.FollowLink
import models.form._
import models.navigation.Menus
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.validation.{Valid, Invalid, Constraint}
import play.api.data.validation.Constraints._
import play.api.mvc._

import scala.concurrent.Future
import scala.util.{Success, Failure, Try}
import scalaz.{\/-, -\/}

/**
 * @author hiral
 */
object Logkafka extends Controller{
  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  private[this] val kafkaManager = KafkaManagerContext.getKafkaManager

  val validateHostname : Constraint[String] = Constraint("validate name") { name =>
    Try {
      kafka.manager.utils.Logkafka.validateHostname(name)
    } match {
      case Failure(t) => Invalid(t.getMessage)
      case Success(_) => Valid
    }
  }

  val validatePath: Constraint[String] = Constraint("validate path") { name =>
    Try {
      kafka.manager.utils.Logkafka.validatePath(name)
    } match {
      case Failure(t) => Invalid(t.getMessage)
      case Success(_) => Valid
    }
  }
  
  val kafka_0_8_1_1_Default = CreateLogkafka("","",
      LogkafkaNewConfigs.configMaps(Kafka_0_8_1_1).map{case(k,v) => LKConfig(k,Some(v))}.toList)
  val kafka_0_8_2_0_Default = CreateLogkafka("","",
      LogkafkaNewConfigs.configMaps(Kafka_0_8_2_0).map{case(k,v) => LKConfig(k,Some(v))}.toList)
  val kafka_0_8_2_1_Default = CreateLogkafka("","",
      LogkafkaNewConfigs.configMaps(Kafka_0_8_2_1).map{case(k,v) => LKConfig(k,Some(v))}.toList)

  val defaultCreateForm = Form(
    mapping(
      "hostname" -> nonEmptyText.verifying(maxLength(250), validateHostname),
      "log_path" -> nonEmptyText.verifying(maxLength(250), validatePath),
      "configs" -> list(
        mapping(
          "name" -> nonEmptyText,
          "value" -> optional(text)
        )(LKConfig.apply)(LKConfig.unapply)
      )
    )(CreateLogkafka.apply)(CreateLogkafka.unapply)
  )
  
  val defaultDeleteForm = Form(
    mapping(
      "hostname" -> nonEmptyText.verifying(maxLength(250), validateHostname),
      "log_path" -> nonEmptyText.verifying(maxLength(250), validatePath)
    )(DeleteLogkafka.apply)(DeleteLogkafka.unapply)
  )

  val defaultUpdateConfigForm = Form(
    mapping(
      "hostname" -> nonEmptyText.verifying(maxLength(250), validateHostname),
      "log_path" -> nonEmptyText.verifying(maxLength(250), validatePath),
      "configs" -> list(
        mapping(
          "name" -> nonEmptyText,
          "value" -> optional(text)
        )(LKConfig.apply)(LKConfig.unapply)
      )
    )(UpdateLogkafkaConfig.apply)(UpdateLogkafkaConfig.unapply)
  )

  private def createLogkafkaForm(clusterName: String) = {
    kafkaManager.getClusterConfig(clusterName).map { errorOrConfig =>
      errorOrConfig.map { clusterConfig =>
        clusterConfig.version match {
          case Kafka_0_8_1_1 => defaultCreateForm.fill(kafka_0_8_1_1_Default)
          case Kafka_0_8_2_0 => defaultCreateForm.fill(kafka_0_8_2_0_Default)
          case Kafka_0_8_2_1 => defaultCreateForm.fill(kafka_0_8_2_1_Default)
        }
      }
    }
  }

  def logkafkas(c: String) = Action.async {
    kafkaManager.getLogkafkaListExtended(c).map { errorOrLogkafkaList =>
      Ok(views.html.logkafka.logkafkaList(c,errorOrLogkafkaList))
    }
  }

  def logkafka(c: String, h: String, l:String) = Action.async {
    kafkaManager.getLogkafkaIdentity(c,h).map { errorOrLogkafkaIdentity =>
      Ok(views.html.logkafka.logkafkaView(c,h,l,errorOrLogkafkaIdentity))
    }
  }

  def createLogkafka(clusterName: String) = Action.async { implicit request =>
    createLogkafkaForm(clusterName).map { errorOrForm =>
      Ok(views.html.logkafka.createLogkafka(clusterName, errorOrForm))
    }
  }

  def handleCreateLogkafka(clusterName: String) = Action.async { implicit request =>
    defaultCreateForm.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(views.html.logkafka.createLogkafka(clusterName,\/-(formWithErrors)))),
      cl => {
        val props = new Properties()
        cl.configs.filter(_.value.isDefined).foreach(c => props.setProperty(c.name,c.value.get))
        kafkaManager.createLogkafka(clusterName,cl.hostname,cl.log_path,props).map { errorOrSuccess =>
          Ok(views.html.common.resultOfCommand(
            views.html.navigation.clusterMenu(clusterName,"Logkafka","Create",Menus.clusterMenus(clusterName)),
            models.navigation.BreadCrumbs.withNamedViewAndCluster("Logkafkas",clusterName,"Create Logkafka"),
            errorOrSuccess,
            "Create Logkafka",
            FollowLink("Go to hostname view.",routes.Logkafka.logkafka(clusterName, cl.hostname, cl.log_path).toString()),
            FollowLink("Try again.",routes.Logkafka.createLogkafka(clusterName).toString())
          ))
        }
      }
    )
  }

  def handleDeleteLogkafka(clusterName: String, hostname: String, log_path: String) = Action.async { implicit request =>
    defaultDeleteForm.bindFromRequest.fold(
      formWithErrors => Future.successful(
        BadRequest(views.html.logkafka.logkafkaView(
          clusterName,
          hostname,
          log_path,
          -\/(ApiError(formWithErrors.error("logkafka").map(_.toString).getOrElse("Unknown error deleting logkafka!")))))),
      deleteLogkafka => {
        kafkaManager.deleteLogkafka(clusterName,deleteLogkafka.hostname,deleteLogkafka.log_path).map { errorOrSuccess =>
          Ok(views.html.common.resultOfCommand(
            views.html.navigation.clusterMenu(clusterName,"Logkafka","Logkafka View",Menus.clusterMenus(clusterName)),
            models.navigation.BreadCrumbs.withNamedViewAndClusterAndLogkafka("Logkafka View",clusterName,hostname,log_path,"Delete Logkafka"),
            errorOrSuccess,
            "Delete Logkafka",
            FollowLink("Go to logkafka list.",routes.Logkafka.logkafkas(clusterName).toString()),
            FollowLink("Try again.",routes.Logkafka.logkafka(clusterName, hostname, log_path).toString())
          ))
        }
      }
    )
  }

  private def updateConfigForm(clusterName: String, log_path: String, li: LogkafkaIdentity) = {
    kafkaManager.getClusterConfig(clusterName).map { errorOrConfig =>
      errorOrConfig.map { clusterConfig =>
        val defaultConfigMap = clusterConfig.version match {
          case Kafka_0_8_1_1 => LogkafkaNewConfigs.configNames(Kafka_0_8_1_1).map(n => (n,LKConfig(n,None))).toMap
          case Kafka_0_8_2_0 => LogkafkaNewConfigs.configNames(Kafka_0_8_2_0).map(n => (n,LKConfig(n,None))).toMap
          case Kafka_0_8_2_1 => LogkafkaNewConfigs.configNames(Kafka_0_8_2_1).map(n => (n,LKConfig(n,None))).toMap
        }
        val identityOption = li.identityMap.get(log_path)
        if (identityOption.isDefined) {
          val configOption = identityOption.get._1
          if (configOption.isDefined) {
            val config: Map[String, String] = configOption.get
            val combinedMap = defaultConfigMap ++ config.map(tpl => tpl._1 -> LKConfig(tpl._1,Option(tpl._2)))
            defaultUpdateConfigForm.fill(UpdateLogkafkaConfig(li.hostname,log_path,combinedMap.toList.map(_._2)))
          } else {
            defaultUpdateConfigForm.fill(UpdateLogkafkaConfig(li.hostname,log_path,List(LKConfig("",None))))
          }
        } else {
          defaultUpdateConfigForm.fill(UpdateLogkafkaConfig(li.hostname,log_path,List(LKConfig("",None))))
        }
      }
    }
  }

  def updateConfig(clusterName: String, hostname: String, log_path: String) = Action.async { implicit request =>
    val errorOrFormFuture = kafkaManager.getLogkafkaIdentity(clusterName, hostname).flatMap { errorOrLogkafkaIdentity =>
      errorOrLogkafkaIdentity.fold( e => Future.successful(-\/(e)) ,{ logkafkaIdentity =>
        updateConfigForm(clusterName, log_path, logkafkaIdentity)
      })
    }
    errorOrFormFuture.map { errorOrForm =>
      Ok(views.html.logkafka.updateConfig(clusterName, hostname, log_path, errorOrForm))
    }
  }

  def handleUpdateConfig(clusterName: String, hostname: String, log_path: String) = Action.async { implicit request =>
    defaultUpdateConfigForm.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(views.html.logkafka.updateConfig(clusterName, hostname, log_path, \/-(formWithErrors)))),
      updateLogkafkaConfig => {
        val props = new Properties()
        updateLogkafkaConfig.configs.filter(_.value.isDefined).foreach(c => props.setProperty(c.name,c.value.get))
        kafkaManager.updateLogkafkaConfig(clusterName,updateLogkafkaConfig.hostname,updateLogkafkaConfig.log_path, props).map { errorOrSuccess =>
          Ok(views.html.common.resultOfCommand(
            views.html.navigation.clusterMenu(clusterName,"Logkafka","Logkafka View",Menus.clusterMenus(clusterName)),
            models.navigation.BreadCrumbs.withNamedViewAndClusterAndLogkafka("Logkafka View",clusterName, hostname, log_path, "Update Config"),
            errorOrSuccess,
            "Update Config",
            FollowLink("Go to logkafka view.",routes.Logkafka.logkafka(clusterName, updateLogkafkaConfig.hostname, updateLogkafkaConfig.log_path).toString()),
            FollowLink("Try again.",routes.Logkafka.updateConfig(clusterName, hostname, log_path).toString())
          ))
        }
      }
    )
  }
}
