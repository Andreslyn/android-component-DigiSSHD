/**
 * DigiSSHD - DigiControl component for Android Platform
 * Copyright (c) 2012, Alexey Aksenov ezh@ezh.msk.ru. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 or any later
 * version, as published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 3 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 3 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

package org.digimead.digi.ctrl.sshd

import java.io.File
import java.io.FileFilter
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicReference

import scala.Option.option2Iterable
import scala.actors.Futures.future
import scala.annotation.elidable
import scala.collection.JavaConversions.mapAsScalaMap
import scala.collection.JavaConversions.seqAsJavaList
import scala.ref.WeakReference
import scala.util.Random

import org.digimead.digi.ctrl.lib.aop.Loggable
import org.digimead.digi.ctrl.lib.base.AppComponent
import org.digimead.digi.ctrl.lib.base.AppControl
import org.digimead.digi.ctrl.lib.declaration.DIntent
import org.digimead.digi.ctrl.lib.declaration.DPreference
import org.digimead.digi.ctrl.lib.declaration.DTimeout
import org.digimead.digi.ctrl.lib.dialog.FileChooser
import org.digimead.digi.ctrl.lib.dialog.InstallControl
import org.digimead.digi.ctrl.lib.log.Logging
import org.digimead.digi.ctrl.lib.message.IAmMumble
import org.digimead.digi.ctrl.lib.message.IAmWarn
import org.digimead.digi.ctrl.lib.message.IAmYell
import org.digimead.digi.ctrl.lib.util.Android
import org.digimead.digi.ctrl.lib.util.Common
import org.digimead.digi.ctrl.lib.util.Passwords
import org.digimead.digi.ctrl.lib.util.SyncVar
import org.digimead.digi.ctrl.sshd.Message.dispatcher

import android.app.AlertDialog
import android.app.ListActivity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.text.ClipboardManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.Base64
import android.view.ContextMenu
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import annotation.elidable.ASSERTION

protected case class User(name: String, password: String, home: String, enabled: Boolean) extends Parcelable {
  def this(in: Parcel) = this(name = in.readString,
    password = in.readString,
    home = in.readString,
    enabled = (in.readByte == 1))
  def writeToParcel(out: Parcel, flags: Int) {
    User.log.debug("writeToParcel SSHDUsers.User with flags " + flags)
    out.writeString(name)
    out.writeString(password)
    out.writeString(home)
    out.writeByte(if (enabled) 1 else 0)
  }
  def describeContents() = 0
  def save(context: Context) {
    val userPref = context.getSharedPreferences(DPreference.Users, Context.MODE_PRIVATE)
    val editor = userPref.edit
    editor.putString(name, Base64.encodeToString(Common.parcelToArray(this), Base64.DEFAULT))
    editor.commit
  }
  def remove(context: Context) {
    val userPref = context.getSharedPreferences(DPreference.Users, Context.MODE_PRIVATE)
    val editor = userPref.edit
    editor.remove(name)
    editor.commit
  }
}

protected object User extends Logging {
  override val log = Logging.getLogger(this)
  final val CREATOR: Parcelable.Creator[User] = new Parcelable.Creator[User]() {
    def createFromParcel(in: Parcel): User = try {
      log.debug("createFromParcel new SSHDUsers.User")
      new User(in)
    } catch {
      case e =>
        log.error(e.getMessage, e)
        null
    }
    def newArray(size: Int): Array[User] = new Array[User](size)
  }
}

class SSHDUsers extends ListActivity with Logging with Passwords {
  private lazy val inflater = getLayoutInflater()
  private lazy val buttonGrowShrink = new WeakReference(findViewById(R.id.buttonGrowShrink).asInstanceOf[ImageButton])
  private lazy val dynamicHeader = new WeakReference({
    findViewById(R.id.users_header).asInstanceOf[LinearLayout]
  })
  private lazy val dynamicFooter = new WeakReference({
    findViewById(R.id.users_footer).asInstanceOf[LinearLayout].
      findViewById(R.id.users_footer_dynamic).asInstanceOf[LinearLayout]
  })
  private lazy val apply = new WeakReference(findViewById(R.id.users_footer).findViewById(R.id.users_apply).asInstanceOf[TextView])
  private lazy val blockAll = new WeakReference(findViewById(R.id.users_footer).findViewById(R.id.users_footer_toggle_all).asInstanceOf[TextView])
  private lazy val deleteAll = new WeakReference(findViewById(R.id.users_footer).findViewById(R.id.users_footer_delete_all).asInstanceOf[TextView])
  private lazy val userName = new WeakReference(dynamicFooter.get.map(_.findViewById(R.id.users_name).asInstanceOf[TextView]).getOrElse(null))
  private lazy val userHome = new WeakReference(dynamicFooter.get.map(_.findViewById(R.id.users_home).asInstanceOf[TextView]).getOrElse(null))
  private lazy val userPassword = new WeakReference(dynamicFooter.get.map(_.findViewById(R.id.users_password).asInstanceOf[TextView]).getOrElse(null))
  private lazy val userPasswordShowButton = new WeakReference(dynamicFooter.get.map(_.findViewById(R.id.users_show_password).asInstanceOf[ImageButton]).getOrElse(null))
  private val lastActiveUser = new AtomicReference[Option[User]](None)
  log.debug("alive")

  @Loggable
  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.users)
    SSHDUsers.adapter.foreach(setListAdapter)
    for {
      userName <- userName.get
      userHome <- userHome.get
      userPassword <- userPassword.get
    } {
      userName.addTextChangedListener(new TextWatcher() {
        def afterTextChanged(s: Editable) { updateFieldsState }
        def beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        def onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
      })
      userHome.addTextChangedListener(new TextWatcher() {
        def afterTextChanged(s: Editable) { updateFieldsState }
        def beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        def onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
      })
      userPassword.addTextChangedListener(new TextWatcher() {
        def afterTextChanged(s: Editable) { updateFieldsState }
        def beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        def onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
      })
    }
    val lv = getListView()
    registerForContextMenu(getListView())
  }
  @Loggable
  override def onStart() {
    super.onStart()
  }
  @Loggable
  override def onResume() {
    super.onResume()
    for {
      dynamicHeader <- dynamicHeader.get
      dynamicFooter <- dynamicFooter.get
      buttonGrowShrink <- buttonGrowShrink.get
      ic_grow <- SSHDActivity.ic_grow
      ic_shrink <- SSHDActivity.ic_shrink
    } {
      if (SSHDActivity.collapsed.get) {
        buttonGrowShrink.setBackgroundDrawable(ic_shrink)
        dynamicHeader.setVisibility(View.GONE)
        dynamicFooter.setVisibility(View.GONE)
      } else {
        buttonGrowShrink.setBackgroundDrawable(ic_grow)
        dynamicHeader.setVisibility(View.VISIBLE)
        dynamicFooter.setVisibility(View.VISIBLE)
      }
    }
    updateFieldsState()
  }
  @Loggable
  override def onPause() {
    super.onPause()
  }
  @Loggable
  override def onDestroy() {
    super.onDestroy()
  }
  @Loggable
  override def onListItemClick(l: ListView, v: View, position: Int, id: Long) = for {
    adapter <- SSHDUsers.adapter
    userName <- userName.get
    userHome <- userHome.get
    userPassword <- userPassword.get
  } {
    adapter.getItem(position) match {
      case user: User =>
        lastActiveUser.set(Some(user))
        userName.setText(user.name)
        userHome.setText(user.home)
        userPassword.setText(user.password)
        updateFieldsState()
      case item =>
        log.fatal("unknown item " + item)
    }
  }
  @Loggable
  override def onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo) = for {
    adapter <- SSHDUsers.adapter
  } {
    super.onCreateContextMenu(menu, v, menuInfo)
    menuInfo match {
      case info: AdapterContextMenuInfo =>
        adapter.getItem(info.position) match {
          case item: User =>
            menu.setHeaderTitle(item.name)
            menu.setHeaderIcon(Android.getId(v.getContext, "ic_users", "drawable"))
            if (item.enabled)
              menu.add(Menu.NONE, Android.getId(v.getContext, "users_disable"), 1,
                Android.getString(v.getContext, "users_disable").getOrElse("Disable"))
            else
              menu.add(Menu.NONE, Android.getId(v.getContext, "users_enable"), 1,
                Android.getString(v.getContext, "users_enable").getOrElse("Enable"))
            if (item.name != "android")
              menu.add(Menu.NONE, Android.getId(v.getContext, "users_delete"), 1,
                Android.getString(v.getContext, "users_delete").getOrElse("Delete"))
            menu.add(Menu.NONE, Android.getId(v.getContext, "users_copy_info"), 3,
              Android.getString(v.getContext, "users_copy_info").getOrElse("Copy information"))
          case item =>
            log.fatal("unknown item " + item)
        }
      case info =>
        log.fatal("unsupported menu info " + info)
    }
  }
  @Loggable
  override def onContextItemSelected(menuItem: MenuItem): Boolean = {
    for {
      adapter <- SSHDUsers.adapter
    } yield {
      val info = menuItem.getMenuInfo.asInstanceOf[AdapterContextMenuInfo]
      adapter.getItem(info.position) match {
        case item: User =>
          menuItem.getItemId match {
            case id if id == Android.getId(this, "users_disable") =>
              new AlertDialog.Builder(this).
                setTitle(Android.getString(this, "users_disable_title").getOrElse("Disable user \"%s\"").format(item.name)).
                setMessage(Android.getString(this, "users_disable_message").getOrElse("Do you want to disable \"%s\" account?").format(item.name)).
                setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                  def onClick(dialog: DialogInterface, whichButton: Int) = {
                    val message = Android.getString(SSHDUsers.this, "users_disabled_message").
                      getOrElse("disabled user \"%s\"").format(item.name)
                    IAmWarn(message)
                    Toast.makeText(SSHDUsers.this, message, Toast.LENGTH_SHORT).show()
                    val newUser = item.copy(enabled = false)
                    newUser.save(SSHDUsers.this)
                    val position = adapter.getPosition(item)
                    adapter.remove(item)
                    adapter.insert(newUser, position)
                    updateFieldsState()
                    if (lastActiveUser.get.exists(_ == item))
                      lastActiveUser.set(Some(newUser))
                  }
                }).
                setNegativeButton(android.R.string.cancel, null).
                setIcon(android.R.drawable.ic_dialog_alert).
                create().show()
              true
            case id if id == Android.getId(this, "users_enable") =>
              new AlertDialog.Builder(this).
                setTitle(Android.getString(this, "users_enable_title").getOrElse("Enable user \"%s\"").format(item.name)).
                setMessage(Android.getString(this, "users_enable_message").getOrElse("Do you want to enable \"%s\" account?").format(item.name)).
                setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                  def onClick(dialog: DialogInterface, whichButton: Int) = {
                    val message = Android.getString(SSHDUsers.this, "users_enabled_message").
                      getOrElse("enabled user \"%s\"").format(item.name)
                    IAmWarn(message)
                    Toast.makeText(SSHDUsers.this, message, Toast.LENGTH_SHORT).show()
                    val newUser = item.copy(enabled = true)
                    newUser.save(SSHDUsers.this)
                    val position = adapter.getPosition(item)
                    adapter.remove(item)
                    adapter.insert(newUser, position)
                    updateFieldsState()
                    if (lastActiveUser.get.exists(_ == item))
                      lastActiveUser.set(Some(newUser))
                  }
                }).
                setNegativeButton(android.R.string.cancel, null).
                setIcon(android.R.drawable.ic_dialog_alert).
                create().show()
              true
            case id if id == Android.getId(this, "users_delete") =>
              new AlertDialog.Builder(this).
                setTitle(Android.getString(this, "users_delete_title").getOrElse("Delete user \"%s\"").format(item.name)).
                setMessage(Android.getString(this, "users_delete_message").getOrElse("Do you want to delete \"%s\" account?").format(item.name)).
                setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                  def onClick(dialog: DialogInterface, whichButton: Int) = {
                    val message = Android.getString(SSHDUsers.this, "users_deleted_message").
                      getOrElse("deleted user \"%s\"").format(item.name)
                    IAmWarn(message)
                    Toast.makeText(SSHDUsers.this, message, Toast.LENGTH_SHORT).show()
                    adapter.remove(item)
                    item.remove(SSHDUsers.this)
                    updateFieldsState()
                    if (lastActiveUser.get.exists(_ == item))
                      lastActiveUser.set(None)
                  }
                }).
                setNegativeButton(android.R.string.cancel, null).
                setIcon(android.R.drawable.ic_dialog_alert).
                create().show()
              true
            case id if id == Android.getId(this, "users_copy_info") =>
              try {
                val message = Android.getString(SSHDUsers.this, "users_copy_info").
                  getOrElse("Copy information ablout \"%s\" to clipboard").format(item.name)
                runOnUiThread(new Runnable {
                  def run = try {
                    val clipboard = SSHDUsers.this.getSystemService(Context.CLIPBOARD_SERVICE).asInstanceOf[ClipboardManager]
                    clipboard.setText("login: %s\nhome: %s\npassword: %s\nenabled: %s\n".format(item.name, item.home, item.password, item.enabled))
                    Toast.makeText(SSHDUsers.this, message, Toast.LENGTH_SHORT).show()
                  } catch {
                    case e =>
                      IAmYell("Unable to copy to clipboard information about \"" + item.name + "\"", e)
                  }
                })
              } catch {
                case e =>
                  IAmYell("Unable to copy to clipboard information about \"" + item.name + "\"", e)
              }
              true
            case id =>
              log.fatal("unknown action " + id)
              false
          }
        case item =>
          log.fatal("unknown item " + item)
          false
      }
    }
  } getOrElse false
  @Loggable
  def onClickApply(v: View) = synchronized {
    for {
      userName <- userName.get
      userHome <- userHome.get
      userPassword <- userPassword.get
      adapter <- SSHDUsers.adapter
    } {
      val name = userName.getText.toString.trim
      val home = userHome.getText.toString.trim
      val password = userPassword.getText.toString.trim
      assert(name.nonEmpty && home.nonEmpty && password.nonEmpty, "one of user fields is empty")
      lastActiveUser.get match {
        case Some(user) if name == "android" =>
          new AlertDialog.Builder(this).
            setTitle(Android.getString(v.getContext, "users_update_user_title").getOrElse("Update user \"%s\"").format(name)).
            setMessage(Android.getString(v.getContext, "users_update_user_message").getOrElse("Do you want to save the changes?")).
            setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
              def onClick(dialog: DialogInterface, whichButton: Int) = {
                val message = Android.getString(v.getContext, "users_update_message").
                  getOrElse("update user \"%s\"").format(name)
                IAmWarn(message)
                Toast.makeText(v.getContext, message, Toast.LENGTH_SHORT).show()
                val newUser = user.copy(password = password)
                lastActiveUser.set(Some(newUser))
                newUser.save(v.getContext)
                val position = adapter.getPosition(user)
                adapter.remove(user)
                adapter.insert(newUser, position)
                updateFieldsState()
                SSHDUsers.this.getListView.setSelectionFromTop(position, 5)
              }
            }).
            setNegativeButton(android.R.string.cancel, null).
            setIcon(android.R.drawable.ic_dialog_alert).
            create().show()
        case Some(user) if SSHDUsers.list.exists(_.name == name) =>
          new AlertDialog.Builder(this).
            setTitle(Android.getString(v.getContext, "users_update_user_title").getOrElse("Update user \"%s\"").format(name)).
            setMessage(Android.getString(v.getContext, "users_update_user_message").getOrElse("Do you want to save the changes?")).
            setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
              def onClick(dialog: DialogInterface, whichButton: Int) = {
                val message = Android.getString(v.getContext, "users_update_message").
                  getOrElse("update user \"%s\"").format(name)
                IAmWarn(message)
                Toast.makeText(v.getContext, message, Toast.LENGTH_SHORT).show()
                val newUser = user.copy(name = name, password = password, home = home)
                lastActiveUser.set(Some(newUser))
                newUser.save(v.getContext)
                val position = adapter.getPosition(user)
                adapter.remove(user)
                adapter.insert(newUser, position)
                updateFieldsState()
                SSHDUsers.this.getListView.setSelectionFromTop(position, 5)
              }
            }).
            setNegativeButton(android.R.string.cancel, null).
            setIcon(android.R.drawable.ic_dialog_alert).
            create().show()
        case _ =>
          new AlertDialog.Builder(this).
            setTitle(Android.getString(v.getContext, "users_create_user_title").getOrElse("Create user \"%s\"").format(name)).
            setMessage(Android.getString(v.getContext, "users_create_user_message").getOrElse("Do you want to save the changes?")).
            setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
              def onClick(dialog: DialogInterface, whichButton: Int) = {
                val message = Android.getString(v.getContext, "users_create_message").
                  getOrElse("created user \"%s\"").format(name)
                IAmWarn(message)
                Toast.makeText(v.getContext, message, Toast.LENGTH_SHORT).show()
                val newUser = User(name, password, home, true)
                lastActiveUser.set(Some(newUser))
                newUser.save(v.getContext)
                val position = (SSHDUsers.list :+ newUser).sortBy(_.name).indexOf(newUser)
                adapter.insert(newUser, position)
                updateFieldsState()
                SSHDUsers.this.getListView.setSelectionFromTop(position, 5)
              }
            }).
            setNegativeButton(android.R.string.cancel, null).
            setIcon(android.R.drawable.ic_dialog_alert).
            create().show()
      }
    }
  }
  @Loggable
  def onClickToggleBlockAll(v: View) = {
    new AlertDialog.Builder(this).
      setTitle(Android.getString(v.getContext, "users_disable_all_title").getOrElse("Disable all users")).
      setMessage(Android.getString(v.getContext, "users_disable_all_message").getOrElse("Are you sure you want to disable all users?")).
      setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
        def onClick(dialog: DialogInterface, whichButton: Int) = SSHDUsers.this.synchronized {
          for {
            adapter <- SSHDUsers.adapter
          } {
            adapter.setNotifyOnChange(false)
            SSHDUsers.list.foreach(user => {
              IAmMumble("disable user \"%s\"".format(user.name))
              val newUser = user.copy(enabled = false)
              newUser.save(v.getContext)
              val position = adapter.getPosition(user)
              adapter.remove(user)
              adapter.insert(newUser, position)
              if (lastActiveUser.get.exists(_ == user))
                lastActiveUser.set(Some(newUser))
            })
            adapter.setNotifyOnChange(true)
            adapter.notifyDataSetChanged
          }
          val message = Android.getString(v.getContext, "users_all_disabled").getOrElse("all users are disabled")
          IAmWarn(message)
          Toast.makeText(v.getContext, message, Toast.LENGTH_SHORT).show()
          updateFieldsState
        }
      }).
      setNegativeButton(android.R.string.cancel, null).
      setIcon(android.R.drawable.ic_dialog_alert).
      create().show()
  }
  @Loggable
  def onClickDeleteAll(v: View) = {
    new AlertDialog.Builder(this).
      setTitle(Android.getString(v.getContext, "users_delete_all_title").getOrElse("Delete all users")).
      setMessage(Android.getString(v.getContext, "users_delete_all_message").getOrElse("Are you sure you want to delete all users except \"android\"?")).
      setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
        def onClick(dialog: DialogInterface, whichButton: Int) = SSHDUsers.this.synchronized {
          for {
            adapter <- SSHDUsers.adapter
          } {
            adapter.setNotifyOnChange(false)
            SSHDUsers.list.foreach(user => if (user.name != "android") {
              IAmMumble("remove \"%s\" user account".format(user.name))
              user.remove(v.getContext)
              adapter.remove(user)
              if (lastActiveUser.get.exists(_ == user))
                lastActiveUser.set(None)
            })
            adapter.setNotifyOnChange(true)
            adapter.notifyDataSetChanged
          }
          val message = Android.getString(v.getContext, "users_all_deleted").getOrElse("all users except \"android\" are deleted")
          IAmWarn(message)
          Toast.makeText(v.getContext, message, Toast.LENGTH_SHORT).show()
          updateFieldsState
        }
      }).
      setNegativeButton(android.R.string.cancel, null).
      setIcon(android.R.drawable.ic_dialog_alert).
      create().show()
  }
  @Loggable
  def onClickGrowShrink(v: View) = {
    if (SSHDActivity.collapsed.get) {
      for {
        buttonGrowShrink <- buttonGrowShrink.get
        ic_grow <- SSHDActivity.ic_grow
      } buttonGrowShrink.setBackgroundDrawable(ic_grow)
      SSHDActivity.collapsed.set(false)
    } else {
      for {
        buttonGrowShrink <- buttonGrowShrink.get
        ic_shrink <- SSHDActivity.ic_shrink
      } buttonGrowShrink.setBackgroundDrawable(ic_shrink)
      SSHDActivity.collapsed.set(true)
    }
    for {
      dynamicHeader <- dynamicHeader.get
      dynamicFooter <- dynamicFooter.get
    } {
      if (SSHDActivity.collapsed.get) {
        dynamicHeader.setVisibility(View.GONE)
        dynamicFooter.setVisibility(View.GONE)
      } else {
        dynamicHeader.setVisibility(View.VISIBLE)
        dynamicFooter.setVisibility(View.VISIBLE)
      }
    }
  }
  @Loggable
  def onClickDigiControl(v: View) = try {
    val intent = new Intent(DIntent.HostActivity)
    startActivity(intent)
  } catch {
    case e =>
      IAmYell("Unable to open activity for " + DIntent.HostActivity, e)
      AppComponent.Inner.showDialogSafe(this, InstallControl.getId(this))
  }
  @Loggable
  def onClickGenerateNewUser(v: View) = future {
    try {
      lastActiveUser.set(None)
      // name
      val names = getResources.getStringArray(R.array.names)
      val rand = new Random(System.currentTimeMillis())
      val random_index = rand.nextInt(names.length)
      val name = {
        var name = ""
        while (name.isEmpty || SSHDUsers.list.exists(_.name == name)) {
          val rawName = names(random_index)
          name = (SSHDUsers.nameMaximumLength - rawName.length) match {
            case len if len >= 4 =>
              rawName + randomInt(0, 9999)
            case len if len > 3 =>
              rawName + randomInt(0, 999)
            case len if len > 2 =>
              rawName + randomInt(0, 99)
            case len if len > 1 =>
              rawName + randomInt(0, 9)
            case _ =>
              rawName
          }
        }
        name
      }
      // home
      val internalPath = new SyncVar[File]()
      val externalPath = new SyncVar[File]()
      AppControl.Inner.callListDirectories(getPackageName)() match {
        case Some((internal, external)) =>
          internalPath.set(new File(internal))
          externalPath.set(new File(external))
        case r =>
          log.warn("unable to get component directories, result " + r)
          internalPath.set(null)
          externalPath.set(null)
      }
      val home = externalPath.get(DTimeout.normal).getOrElse({
        val sdcard = new File("/sdcard")
        if (!sdcard.exists)
          new File("/")
        else
          sdcard
      }).getAbsolutePath
      // password
      val password = generate()
      for {
        userName <- userName.get
        userHome <- userHome.get
        userPasswrod <- userPassword.get
      } {
        runOnUiThread(new Runnable {
          def run {
            userName.setText(name)
            userHome.setText(home)
            userPasswrod.setText(password)
            updateFieldsState()
          }
        })
      }
    } catch {
      case e =>
        log.error(e.getMessage, e)
    }
  }
  @Loggable
  def onClickChangeHomeDirectory(v: View): Unit = userHome.get.foreach {
    userHome =>
      if (lastActiveUser.get.exists(_.name == "android")) {
        Toast.makeText(v.getContext, Android.getString(v.getContext, "users_home_android_warning").
          getOrElse("unable to change home directory of system user"), Toast.LENGTH_SHORT).show()
        return
      }
      val filter = new FileFilter { override def accept(file: File) = file.isDirectory }
      val userHomeString = userHome.getText.toString.trim
      if (userHomeString.isEmpty) {
        Toast.makeText(v.getContext, Android.getString(v.getContext, "users_home_directory_empty").
          getOrElse("home directory is empty"), Toast.LENGTH_SHORT).show()
        return
      }
      val userHomeFile = new File(userHomeString)
      if (!userHomeFile.exists) {
        new AlertDialog.Builder(this).
          setTitle(R.string.users_home_directory_not_exists_title).
          setMessage(R.string.users_home_directory_not_exists_message).
          setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            def onClick(dialog: DialogInterface, whichButton: Int) =
              if (userHomeFile.mkdirs) {
                dialog.dismiss()
                FileChooser.createDialog(SSHDUsers.this, Android.getString(SSHDUsers.this, "dialog_select_folder").
                  getOrElse("Select Folder"), userHomeFile, (path, files) => onResultChangeHomeDirectory(path, files), filter).show()
              } else {
                Toast.makeText(v.getContext, Android.getString(v.getContext, "filechooser_create_directory_failed").
                  getOrElse("unable to create directory %s").format(userHomeFile), Toast.LENGTH_SHORT).show()
              }
          }).
          setNegativeButton(android.R.string.cancel, null).
          setIcon(android.R.drawable.ic_dialog_alert).
          create().show()
      } else {
        FileChooser.createDialog(this, Android.getString(this, "dialog_select_folder").getOrElse("Select Folder"),
          userHomeFile, (path, files) => onResultChangeHomeDirectory(path, files), filter).show()
      }
  }
  @Loggable
  def onResultChangeHomeDirectory(path: File, files: Seq[File]) = userHome.get.foreach {
    userHome =>
      userHome.setText(path.toString)
  }
  @Loggable
  def onClickShowPassword(v: View): Unit = for {
    userPasswordShowButton <- userPasswordShowButton.get
    userPassword <- userPassword.get
  } {
    if (SSHDUsers.showPassword) {
      SSHDUsers.showPassword = false
      userPasswordShowButton.setSelected(false)
      userPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
      userPassword.setTransformationMethod(PasswordTransformationMethod.getInstance())
    } else {
      SSHDUsers.showPassword = true
      userPasswordShowButton.setSelected(true)
      userPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    }
  }
  @Loggable
  private def updateFieldsState() = for {
    userName <- userName.get
    userHome <- userHome.get
    userPassword <- userPassword.get
    apply <- apply.get
    blockAll <- blockAll.get
    deleteAll <- deleteAll.get
  } {
    // set userName userHome userPassword
    if (lastActiveUser.get.exists(_.name == "android")) {
      userName.setEnabled(false)
      userHome.setEnabled(false)
      userPassword.setEnabled(true)
    } else {
      userName.setEnabled(true)
      userHome.setEnabled(true)
      userPassword.setEnabled(true)
    }
    // set apply
    if (lastActiveUser.get.nonEmpty) {
      if (lastActiveUser.get.exists(u =>
        u.name == userName.getText.toString.trim &&
          u.home.toString == userHome.getText.toString.trim &&
          u.password == userPassword.getText.toString.trim))
        apply.setEnabled(false)
      else if (userName.getText.toString.trim.nonEmpty &&
        userHome.getText.toString.trim.nonEmpty &&
        userPassword.getText.toString.trim.nonEmpty)
        apply.setEnabled(true)
      else
        apply.setEnabled(false)
    } else {
      if (userName.getText.toString.trim.nonEmpty &&
        userHome.getText.toString.trim.nonEmpty &&
        userPassword.getText.toString.trim.nonEmpty) {
        apply.setEnabled(true)
      } else
        apply.setEnabled(false)
    }
    // set block all
    if (SSHDUsers.list.exists(_.enabled))
      blockAll.setEnabled(true)
    else
      blockAll.setEnabled(false)
    // set delete all
    if (SSHDUsers.list.exists(_.name != "android"))
      deleteAll.setEnabled(true)
    else
      deleteAll.setEnabled(false)

  }
}

object SSHDUsers extends Logging {
  @volatile private var activity: Option[SSHDUsers] = None
  private val nameMaximumLength = 16
  @volatile private var showPassword = false
  private lazy val adapter: Option[ArrayAdapter[User]] = SSHDActivity.activity.map {
    activity =>
      val userPref = activity.getSharedPreferences(DPreference.Users, Context.MODE_PRIVATE)
      val users = userPref.getAll.map({
        case (name, data) => try {
          Common.unparcelFromArray[User](Base64.decode(data.asInstanceOf[String], Base64.DEFAULT),
            User.getClass.getClassLoader)
        } catch {
          case e =>
            log.warn(e.getMessage, e)
            None
        }
      }).flatten.toList
      Some(new ArrayAdapter[User](activity, R.layout.users_row, android.R.id.text1,
        new ArrayList[User](checkAndroidUser(users).sortBy(_.name))) {
        override def getView(position: Int, convertView: View, parent: ViewGroup): View = {
          val item = getItem(position)
          val view = super.getView(position, convertView, parent)
          val text1 = view.findViewById(android.R.id.text1).asInstanceOf[TextView]
          val text2 = view.findViewById(android.R.id.text2).asInstanceOf[TextView]
          val checkbox = view.findViewById(android.R.id.checkbox).asInstanceOf[CheckedTextView]
          text1.setText(item.name)
          text2.setText(Android.getString(view.getContext, "users_home_at").getOrElse("Home at '%s'").format(item.home))
          checkbox.setChecked(item.enabled)
          view
        }
      })
  } getOrElse { log.fatal("unable to create SSHDUsers adapter"); None }
  log.debug("alive")

  def list(): List[User] = adapter.map(adapter =>
    (for (i <- 0 until adapter.getCount) yield adapter.getItem(i)).toList).getOrElse(List())
  @Loggable
  private def checkAndroidUser(in: List[User]): List[User] = if (!in.exists(_.name == "android")) {
    log.debug("add default system user \"android\"")
    in :+ User("android", "123", "variable location", true)
  } else
    in
}
