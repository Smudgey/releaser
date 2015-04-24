/*
 * Copyright 2015 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.releaser

import java.io.{File, FileOutputStream}
import java.nio.file.{Path, Files, Paths}
import java.util.jar._
import java.util.zip.{ZipEntry, ZipFile, ZipOutputStream}

import com.google.common.io.ByteStreams

import scala.collection.JavaConversions._
import scala.util.{Success, Failure, Try}
import resource._

import scala.xml._
import scala.xml.transform.{RewriteRule, RuleTransformer}

trait Transformer{
  def apply(localFile: Path, targetVersion: String, targetFileName: String): Try[Path]
}

trait XmlTransformer extends Transformer{

  def stagingDir:Path

  def apply(localPomFile: Path, targetVersion: String, targetFileName: String): Try[Path] =  {
    val updatedT: Try[Node] = updateVersion(XML.loadFile(localPomFile.toFile), targetVersion)
    updatedT.flatMap { updated => Try{
      val targetFile = stagingDir.resolve(targetFileName)
      Files.write(targetFile, updated.mkString.getBytes)
    }}
  }

  def updateVersion(node: Node, newVersion:String): Try[Node]

}

class PomTransformer(val stagingDir:Path) extends XmlTransformer{

  def updateVersion(node: Node, newVersion:String): Try[Node] = {
    if((node \ "version").isEmpty){
      Failure(new Exception("Didn't find project element in pom file"))
    } else {

      def updateVersionRec(node: Node, newVersion:String): Node = node match {
        case <project>{ n @ _* }</project> => <project>{ n.map { a => updateVersionRec(a, newVersion) }} </project>
        case <version>{ _* }</version> => <version>{ newVersion }</version>
        case other @ _ => other
      }
      Try { updateVersionRec(node, newVersion)}
    }
  }
}


class IvyTransformer(val stagingDir:Path) extends XmlTransformer{

  def updateVersion(node: Node, newVersion:String): Try[Node] = {
    if((node \ "info" \ "@revision").isEmpty){
      Failure(new Exception("Didn't find revision element in ivy file"))
    } else {

      val rewrite = new RuleTransformer(new RewriteRule {
        override def transform(node: Node) = node match {
          case n: Elem if n.label == "info" =>
            n % Attribute("revision", Text(newVersion), n.attributes.remove("revision"))
          case other => other
        }
      })

      Try { rewrite(node) }
    }
  }
}

class ManifestTransformer(stagingDir:Path){

  val versionNumberFields = Set("Git-Describe", "Implementation-Version", "Specification-Version")

  def manifestTransformer(manifest: Manifest, updatedVersionNumber:String): Manifest = {

    manifest.getMainAttributes.keysIterator.foldLeft(new Manifest()) { (newMan, key) =>
      if(versionNumberFields.contains(key.toString)){
        newMan.getMainAttributes.put(key, updatedVersionNumber)
      } else {
        newMan.getMainAttributes.put(key, manifest.getMainAttributes.get(key))
      }
      newMan
    }
  }

  def apply(localJarFile:Path, targetVersion:String, targetJarName:String):Try[Path] = Try {

    val targetFile = stagingDir.resolve(targetJarName)

    for {
      jarFile <- managed(new ZipFile(localJarFile.toFile))
      zout <- managed(new ZipOutputStream(new FileOutputStream(targetFile.toFile)))
    } {

      jarFile.entries().foreach { ze =>

        if (ze.getName == "META-INF/MANIFEST.MF") {
          val newZipEntry = new ZipEntry(ze.getName)
          newZipEntry.setTime(ze.getTime)
          zout.putNextEntry(newZipEntry)
          val newManifest: Manifest = manifestTransformer(new Manifest(jarFile.getInputStream(ze)), targetVersion)
          newManifest.write(zout)
        } else {
          zout.putNextEntry(new ZipEntry(ze))
          ByteStreams.copy(jarFile.getInputStream(ze), zout)
        }
      }
    }

    targetFile
  }
}