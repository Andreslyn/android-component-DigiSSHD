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

import java.io.BufferedReader
import java.io.File
import java.io.FileFilter
import java.io.FileWriter
import java.io.FilenameFilter
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.Array.canBuildFrom
import scala.Option.option2Iterable
import scala.actors.Futures
import scala.actors.Futures.future
import scala.collection.JavaConversions._
import scala.ref.WeakReference
import scala.util.Random

import org.digimead.digi.ctrl.lib.AnyBase
import org.digimead.digi.ctrl.lib.aop.Loggable
import org.digimead.digi.ctrl.lib.base.AppComponent
import org.digimead.digi.ctrl.lib.base.AppControl
import org.digimead.digi.ctrl.lib.declaration.DIntent
import org.digimead.digi.ctrl.lib.declaration.DPreference
import org.digimead.digi.ctrl.lib.declaration.DTimeout
import org.digimead.digi.ctrl.lib.dialog.FileChooser
import org.digimead.digi.ctrl.lib.dialog.InstallControl
import org.digimead.digi.ctrl.lib.info.UserInfo
import org.digimead.digi.ctrl.lib.log.Logging
import org.digimead.digi.ctrl.lib.message.IAmBusy
import org.digimead.digi.ctrl.lib.message.IAmMumble
import org.digimead.digi.ctrl.lib.message.IAmReady
import org.digimead.digi.ctrl.lib.message.IAmWarn
import org.digimead.digi.ctrl.lib.message.IAmYell
import org.digimead.digi.ctrl.lib.message.Origin.anyRefToOrigin
import org.digimead.digi.ctrl.lib.util.Android
import org.digimead.digi.ctrl.lib.util.Common
import org.digimead.digi.ctrl.lib.util.Passwords
import org.digimead.digi.ctrl.sshd.Message.dispatcher
import org.digimead.digi.ctrl.sshd.service.option.AuthentificationMode

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.ListActivity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.text.ClipboardManager
import android.text.Editable
import android.text.Html
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.Base64
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class SSHDUsers extends ListActivity with Logging {
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
  private lazy val userGenerateButton = new WeakReference(dynamicFooter.get.map(_.findViewById(R.id.users_add).asInstanceOf[ImageButton]).getOrElse(null))
  private lazy val userHome = new WeakReference(dynamicFooter.get.map(_.findViewById(R.id.users_home).asInstanceOf[TextView]).getOrElse(null))
  private lazy val userHomeChangeButton = new WeakReference(dynamicFooter.get.map(_.findViewById(R.id.users_change_home).asInstanceOf[ImageButton]).getOrElse(null))
  private lazy val userPassword = new WeakReference(dynamicFooter.get.map(_.findViewById(R.id.users_password).asInstanceOf[TextView]).getOrElse(null))
  private lazy val userPasswordShowButton = new WeakReference(dynamicFooter.get.map(_.findViewById(R.id.users_show_password).asInstanceOf[ImageButton]).getOrElse(null))
  private val lastActiveUserInfo = new AtomicReference[Option[UserInfo]](None)
  log.debug("alive")

  @Loggable
  override def onCreate(savedInstanceState: Bundle) {
    SSHDUsers.activity = Some(this)
    super.onCreate(savedInstanceState)
    AnyBase.init(this, false)
    AnyBase.preventShutdown(this)
    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_BEHIND)
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
      userName.setFilters(Array(SSHDUsers.userNameFilter))
      userHome.addTextChangedListener(new TextWatcher() {
        def afterTextChanged(s: Editable) { updateFieldsState }
        def beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        def onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
      })
      userHome.setFilters(Array(SSHDUsers.userHomeFilter))
      userPassword.addTextChangedListener(new TextWatcher() {
        def afterTextChanged(s: Editable) { updateFieldsState }
        def beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        def onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
      })
      userPassword.setFilters(Array(SSHDUsers.userPasswordFilter))
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
    for { userGenerateButton <- userGenerateButton.get } {
      AuthentificationMode.getStateExt(this) match {
        case AuthentificationMode.AuthType.SingleUser =>
          setTitle(Android.getString(this, "app_name_singleuser").getOrElse("DigiSSHD: Single User Mode"))
          SSHDUsers.multiUser = false
          userGenerateButton.setEnabled(false)
        case AuthentificationMode.AuthType.MultiUser =>
          setTitle(Android.getString(this, "app_name_multiuser").getOrElse("DigiSSHD: Multi User Mode"))
          SSHDUsers.multiUser = true
          userGenerateButton.setEnabled(true)
        case invalid =>
          log.fatal("invalid authenticatin type \"" + invalid + "\"")
          None
      }
    }
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
    SSHDUsers.activity = None
    AnyBase.deinit(this)
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
      case user: UserInfo =>
        if (SSHDUsers.multiUser || user.name == "android") {
          lastActiveUserInfo.set(Some(user))
          userName.setText(user.name)
          userHome.setText(user.home)
          userPassword.setText(user.password)
          updateFieldsState()
        } else
          Toast.makeText(this, Android.getString(this, "users_in_single_user_mode").getOrElse("only android user available in single user mode"), Toast.LENGTH_SHORT).show()
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
          case item: UserInfo =>
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
        case item: UserInfo =>
          menuItem.getItemId match {
            case id if id == Android.getId(this, "users_disable") =>
              SSHDUsers.Dialog.createDialogUserDisable(this, item, (state) => {}).show
              true
            case id if id == Android.getId(this, "users_enable") =>
              SSHDUsers.Dialog.createDialogUserEnable(this, item, (state) => {}).show
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
                    SSHDUsers.remove(SSHDUsers.this, item)
                    updateFieldsState()
                    if (lastActiveUserInfo.get.exists(_ == item))
                      lastActiveUserInfo.set(None)
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
      lastActiveUserInfo.get match {
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
                lastActiveUserInfo.set(Some(newUser))
                SSHDUsers.save(v.getContext, newUser)
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
                lastActiveUserInfo.set(Some(newUser))
                SSHDUsers.save(v.getContext, newUser)
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
                val newUser = UserInfo(name, password, home, true)
                lastActiveUserInfo.set(Some(newUser))
                SSHDUsers.save(v.getContext, newUser)
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
              SSHDUsers.save(v.getContext, newUser)
              val position = adapter.getPosition(user)
              adapter.remove(user)
              adapter.insert(newUser, position)
              if (lastActiveUserInfo.get.exists(_ == user))
                lastActiveUserInfo.set(Some(newUser))
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
              SSHDUsers.remove(v.getContext, user)
              adapter.remove(user)
              if (lastActiveUserInfo.get.exists(_ == user))
                lastActiveUserInfo.set(None)
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
      AppComponent.Inner.showDialogSafe(this, InstallControl.getClass.getName, InstallControl.getId(this))
  }
  @Loggable
  def onClickGenerateNewUser(v: View) = future {
    try {
      lastActiveUserInfo.set(None)
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
              rawName + SSHDUsers.randomInt(0, 9999)
            case len if len > 3 =>
              rawName + SSHDUsers.randomInt(0, 999)
            case len if len > 2 =>
              rawName + SSHDUsers.randomInt(0, 99)
            case len if len > 1 =>
              rawName + SSHDUsers.randomInt(0, 9)
            case _ =>
              rawName
          }
        }
        name
      }
      // home
      val home = AppControl.Inner.getExternalDirectory(DTimeout.normal).flatMap(d => Option(d)).getOrElse({
        val sdcard = new File("/sdcard")
        if (!sdcard.exists)
          new File("/")
        else
          sdcard
      }).getAbsolutePath
      // password
      val password = SSHDUsers.generate()
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
      if (lastActiveUserInfo.get.exists(_.name == "android")) {
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
                  getOrElse("Select Folder"), userHomeFile, onResultChangeHomeDirectory, filter).show()
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
          userHomeFile, onResultChangeHomeDirectory, filter).show()
      }
  }
  @Loggable
  def onResultChangeHomeDirectory(context: Context, path: File, files: Seq[File], stash: AnyRef) = userHome.get.foreach {
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
      userPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
    }
  }
  @Loggable
  private def updateFieldsState(): Unit = for {
    userName <- userName.get
    userHome <- userHome.get
    userPassword <- userPassword.get
    apply <- apply.get
    blockAll <- blockAll.get
    deleteAll <- deleteAll.get
  } {
    // set apply
    if (lastActiveUserInfo.get.nonEmpty) {
      if (lastActiveUserInfo.get.exists(u =>
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
    // set userName userHome userPassword
    if (lastActiveUserInfo.get.exists(_.name == "android")) {
      userName.setEnabled(true)
      userHome.setEnabled(false)
      userPassword.setEnabled(true)
    } else {
      userName.setEnabled(true)
      userHome.setEnabled(true)
      userPassword.setEnabled(true)
    }
    // single user mode
    if (!SSHDUsers.multiUser) {
      userName.setEnabled(false)
      blockAll.setEnabled(false)
      deleteAll.setEnabled(false)
      return
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

object SSHDUsers extends Logging with Passwords {
  @volatile private var activity: Option[SSHDUsers] = None
  @volatile private var multiUser: Boolean = false
  private val nameMaximumLength = 16
  @volatile private var showPassword = false
  private lazy val adapter: Option[ArrayAdapter[UserInfo]] = AppComponent.Context map {
    context =>
      val userPref = context.getSharedPreferences(DPreference.Users, Context.MODE_PRIVATE)
      val users = userPref.getAll.map({
        case (name, data) => try {
          Common.unparcelFromArray[UserInfo](Base64.decode(data.asInstanceOf[String], Base64.DEFAULT),
            UserInfo.getClass.getClassLoader)
        } catch {
          case e =>
            log.warn(e.getMessage, e)
            None
        }
      }).flatten.toList
      Some(new ArrayAdapter[UserInfo](context, R.layout.users_row, android.R.id.text1,
        new ArrayList[UserInfo](checkAndroidUserInfo(users).sortBy(_.name))) {
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
  val userNameFilter = new InputFilter {
    /*
     * man 5 passwd:
     * The login name may be up to 31 characters long. For compatibility with
     * legacy software, a login name should start with a letter and consist
     * solely of letters, numbers, dashes and underscores. The login name must
     * never begin with a hyphen (`-'); also, it is strongly suggested that nei-
     * ther uppercase characters nor dots (`.') be part of the name, as this
     * tends to confuse mailers. 
     */
    def filter(source: CharSequence, start: Int, end: Int,
      dest: Spanned, dstart: Int, dend: Int): CharSequence = {
      for (i <- start until end)
        if (!(numbers ++ alphabet ++ """_-""").exists(_ == source.charAt(i)))
          return ""
      if (source.toString.nonEmpty && dest.toString.isEmpty &&
        """_-""".exists(_ == source.toString.head))
        return ""
      return null
    }
  }
  val userHomeFilter = new InputFilter {
    def filter(source: CharSequence, start: Int, end: Int,
      dest: Spanned, dstart: Int, dend: Int): CharSequence = {
      for (i <- start until end)
        if ("""|\?*<":>+[]'""".toList.exists(_ == source.charAt(i)))
          return ""
      return null
    }
  }
  val userPasswordFilter = new InputFilter {
    def filter(source: CharSequence, start: Int, end: Int,
      dest: Spanned, dstart: Int, dend: Int): CharSequence = {
      if (dest.length > 15)
        return ""
      for (i <- start until end)
        if (!defaultPasswordCharacters.exists(_ == source.charAt(i)))
          return ""
      return null
    }
  }
  log.debug("alive")

  def list(): List[UserInfo] = adapter.map(adapter =>
    (for (i <- 0 until adapter.getCount) yield adapter.getItem(i)).toList).getOrElse(List())
  @Loggable
  def find(context: Context, name: String): Option[UserInfo] = {
    context.getSharedPreferences(DPreference.Users, Context.MODE_PRIVATE).getString(name, null) match {
      case data: String =>
        Common.unparcelFromArray[UserInfo](Base64.decode(data.asInstanceOf[String], Base64.DEFAULT), UserInfo.getClass.getClassLoader)
      case null if name == "android" =>
        checkAndroidUserInfo(List()).find(_.name == "android")
      case null =>
        None
    }
  }
  @Loggable
  def save(context: Context, user: UserInfo) {
    val userPref = context.getSharedPreferences(DPreference.Users, Context.MODE_PRIVATE)
    val editor = userPref.edit
    editor.putString(user.name, Base64.encodeToString(Common.parcelToArray(user), Base64.DEFAULT))
    editor.commit
  }
  @Loggable
  def remove(context: Context, user: UserInfo) {
    val userPref = context.getSharedPreferences(DPreference.Users, Context.MODE_PRIVATE)
    val editor = userPref.edit
    editor.remove(user.name)
    editor.commit
  }
  @Loggable
  def homeDirectory(context: Context, user: UserInfo): File =
    AppControl.Inner.getExternalDirectory(DTimeout.long) getOrElse new File("/")
  @Loggable
  private def checkAndroidUserInfo(in: List[UserInfo]): List[UserInfo] = if (!in.exists(_.name == "android")) {
    log.debug("add default system user \"android\"")
    in :+ UserInfo("android", "123", "variable location", true)
  } else
    in
  object Key {
    @Loggable
    def getDropbearKeyFile(context: Context, user: UserInfo): Option[File] = AppComponent.Inner.appNativePath flatMap {
      appNativePath =>
        if (user.name != "android") {
          val homeDir = new File(user.home)
          val sshDir = new File(homeDir, ".ssh")
          Some(new File(sshDir, "dropbear_user_key." + Uri.encode(user.name)))
        } else {
          AppControl.Inner.getExternalDirectory(DTimeout.long).flatMap {
            androidHome =>
              val sshDir = new File(androidHome, ".ssh")
              Some(new File(sshDir, "dropbear_user_key." + Uri.encode(user.name)))
          }
        }
    }
    @Loggable def getPublicKeyFile(context: Context, user: UserInfo): Option[File] =
      getDropbearKeyFile(context, user).map(file => new File(file.getParentFile, "public_user_key." + Uri.encode(user.name)))
    @Loggable def getAuthorizedKeysFile(context: Context, user: UserInfo): Option[File] =
      getDropbearKeyFile(context, user).map(file => new File(file.getParentFile, "authorized_keys"))
    @Loggable def getOpenSSHKeyFile(context: Context, user: UserInfo): Option[File] =
      getDropbearKeyFile(context, user).map(file => new File(file.getParentFile, "openssh_user_key." + Uri.encode(user.name)))
    @Loggable
    def importKey(activity: Activity, user: UserInfo) {
      AppComponent.Inner.showDialogSafe[Dialog](activity, "service_import_userkey_dialog", () => {
        val dialog = FileChooser.createDialog(activity,
          Android.getString(activity, "dialog_import_key").getOrElse("Import public key"),
          new File("/"),
          importKeyOnResult,
          new FileFilter { override def accept(file: File) = true },
          importKeyOnClick,
          false,
          user)
        dialog.show()
        dialog
      })
    }
    def importKeyOnClick(context: Context, file: File): Boolean = try {
      if (scala.io.Source.fromFile(file).getLines.filter(_.startsWith("ssh-")).isEmpty) {
        Toast.makeText(context, "public key \"" + file.getName + "\" is broken", Toast.LENGTH_LONG).show
        false
      } else
        true
    } catch {
      case e =>
        Toast.makeText(context, "public key \"" + file.getName + "\" is broken", Toast.LENGTH_LONG).show
        false
    }
    @Loggable
    def importKeyOnResult(context: Context, path: File, files: Seq[File], stash: Any) = try {
      val user = stash.asInstanceOf[UserInfo]
      (for {
        importFileFrom <- files.headOption
        importFileTo <- getPublicKeyFile(context, user)
      } yield {
        IAmWarn("import " + importFileFrom.getName + " as " + importFileTo)
        if (importFileTo.exists())
          importFileTo.delete()
        if (!importFileTo.getParentFile.exists)
          importFileTo.getParentFile.mkdirs
        if (Common.copyFile(importFileFrom, importFileTo)) {
          updateAuthorizedKeys(context, user)
          Toast.makeText(context, Android.getString(context, "import_public_key_successful").
            getOrElse("import \"%s\" key succesful").format(importFileFrom.getName), Toast.LENGTH_SHORT).show
        } else
          Toast.makeText(context, Android.getString(context, "import_public_key_failed").
            getOrElse("import \"%s\" key failed").format(importFileFrom.getName), Toast.LENGTH_LONG).show
      }) getOrElse {
        Toast.makeText(context, Android.getString(context, "import_public_key_canceled").getOrElse("import failed"), Toast.LENGTH_LONG).show
      }
    } catch {
      case e =>
        Toast.makeText(context, Android.getString(context, "import_public_key_canceled").getOrElse("import failed"), Toast.LENGTH_LONG).show
        log.error(e.getMessage, e)
    }
    @Loggable
    def exportDropbearKey(context: Context, user: UserInfo) = try {
      getDropbearKeyFile(context, user) match {
        case Some(file) if file.exists && file.length > 0 =>
          IAmMumble("export Dropbear private key")
          val intent = new Intent(Intent.ACTION_SEND)
          intent.setType("application/octet-stream")
          intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
          context.startActivity(Intent.createChooser(intent, Android.getString(context, "export_dropbear_key").getOrElse("Export Dropbear key")))
        case _ =>
          val message = "unable to export unexists/broken Dropbear private key"
          AnyBase.runOnUiThread { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
          IAmWarn(message)
      }
    } catch {
      case e =>
        val message = "unable to export Dropbear key: " + e.getMessage
        AnyBase.runOnUiThread { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        IAmWarn(message)
        log.error(e.getMessage(), e)
    }
    @Loggable
    def exportOpenSSHKey(context: Context, user: UserInfo) = try {
      getOpenSSHKeyFile(context, user) match {
        case Some(file) if file.exists && file.length > 0 =>
          IAmMumble("export OpenSSH private key")
          val intent = new Intent(Intent.ACTION_SEND)
          intent.setType("application/octet-stream")
          intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
          context.startActivity(Intent.createChooser(intent, Android.getString(context, "export_openssh_key").getOrElse("Export OpenSSH key")))
        case _ =>
          val message = "unable to export unexists/broken OpenSSH private key"
          AnyBase.runOnUiThread { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
          IAmWarn(message)
      }
    } catch {
      case e =>
        val message = "unable to export OpenSSH key: " + e.getMessage
        AnyBase.runOnUiThread { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        IAmWarn(message)
        log.error(e.getMessage(), e)
    }
    @Loggable
    def generateDSAKey(context: Context, user: UserInfo): Boolean = try {
      IAmBusy(this, "1024-bit DSA user key generation")
      generatePrivateKey(context, user, "-t", "dss") &&
        writePublicKey(context, user) &&
        writeOpenSSHKey(context, user) &&
        updateAuthorizedKeys(context, user)
    } catch {
      case e =>
        log.error(e.getMessage(), e)
        false
    } finally {
      IAmReady(this, "DSA key generated")
    }
    @Loggable
    def generateRSAKey(context: Context, user: UserInfo, length: Int): Boolean = try {
      IAmBusy(this, length + "-bit RSA user key generation")
      generatePrivateKey(context, user, "-t", "rsa", "-s", length.toString) &&
        writePublicKey(context, user) &&
        writeOpenSSHKey(context, user) &&
        updateAuthorizedKeys(context, user)
    } catch {
      case e =>
        log.error(e.getMessage(), e)
        false
    } finally {
      IAmReady(this, "RSA key generated")
    }
    @Loggable
    def updateAuthorizedKeys(context: Context, user: UserInfo): Boolean = {
      for {
        fileAuthorizedKeys <- getAuthorizedKeysFile(context, user)
      } yield {
        IAmMumble("update authorized_keys file at " + fileAuthorizedKeys.getAbsolutePath())
        var fw: FileWriter = null
        try {
          if (fileAuthorizedKeys.exists())
            fileAuthorizedKeys.delete()
          if (!fileAuthorizedKeys.getParentFile.exists)
            fileAuthorizedKeys.getParentFile.mkdirs
          fw = new FileWriter(fileAuthorizedKeys, false)
          val pathAuthorizedKeys = fileAuthorizedKeys.getParentFile
          val publicKeys = pathAuthorizedKeys.listFiles(new FilenameFilter {
            def accept(dir: File, name: String) = name.startsWith("public_user_key.")
          })
          log.debug("accumulate public keys from: " + publicKeys.map(_.getName.substring(16)).mkString(", "))
          publicKeys.foreach(f => fw.write(scala.io.Source.fromFile(f).getLines.
            filter(_.startsWith("ssh-")).mkString("\n") + "\n"))
          fw.flush
          true
        } catch {
          case e =>
            IAmYell(e.getMessage, e)
            false
        } finally {
          if (fw != null)
            fw.close
        }
      }
    } getOrElse false
    private def generatePrivateKey(context: Context, user: UserInfo, args: String*): Boolean = try {
      (for {
        keyDropbear <- getDropbearKeyFile(context, user)
        path <- AppControl.Inner.getInternalDirectory(DTimeout.long)
      } yield {
        IAmMumble("generate Dropbear key " + keyDropbear.getAbsolutePath())
        val dropbearkey = new File(path, "dropbearkey").getAbsolutePath()
        if (keyDropbear.exists())
          keyDropbear.delete()
        if (!keyDropbear.getParentFile.exists)
          keyDropbear.getParentFile.mkdirs
        val result = {
          val p = Runtime.getRuntime().exec(Array(dropbearkey, "-f", keyDropbear.getAbsolutePath()) ++ args)
          val err = new BufferedReader(new InputStreamReader(p.getErrorStream()))
          p.waitFor()
          val retcode = p.exitValue()
          if (retcode != 0) {
            var error = err.readLine()
            while (error != null) {
              log.error(dropbearkey + " error: " + error)
              IAmYell("dropbearkey: " + error)
              error = err.readLine()
            }
            false
          } else
            true
        }
        if (result)
          keyDropbear.setReadable(true, false)
        result
      }) getOrElse false
    } catch {
      case e =>
        log.error(e.getMessage, e)
        false
    }
    private def writePublicKey(context: Context, user: UserInfo): Boolean = try {
      (for {
        keyDropbear <- getDropbearKeyFile(context, user)
        keyPublic <- getPublicKeyFile(context, user)
        path <- AppControl.Inner.getInternalDirectory(DTimeout.long)
      } yield {
        IAmMumble("write public key to " + keyPublic.getAbsolutePath())
        if (keyDropbear.exists) {
          val dropbearkey = new File(path, "dropbearkey").getAbsolutePath()
          if (keyPublic.exists())
            keyPublic.delete()
          if (!keyPublic.getParentFile.exists)
            keyPublic.getParentFile.mkdirs
          val p = Runtime.getRuntime().exec(Array(dropbearkey, "-f", keyDropbear.getAbsolutePath(), "-y"))
          val err = new BufferedReader(new InputStreamReader(p.getErrorStream()))
          Futures.future {
            var bufferedOutput: OutputStream = null
            try {
              val lines = scala.io.Source.fromInputStream(p.getInputStream()).getLines.filter(_.startsWith("ssh-"))
              Common.writeToFile(keyPublic, lines.mkString("\n") + "\n")
              keyPublic.setReadable(true, false)
            } catch {
              case e =>
                log.error(e.getMessage, e)
            } finally {
              if (bufferedOutput != null)
                bufferedOutput.close
            }
          }
          p.waitFor()
          val retcode = p.exitValue()
          val result = if (retcode != 0) {
            var error = err.readLine()
            while (error != null) {
              log.error(dropbearkey + " error: " + error)
              IAmYell("dropbearkey: " + error)
              error = err.readLine()
            }
            false
          } else
            true
          if (result)
            keyPublic.setReadable(true, false)
          result
        } else {
          IAmYell("private key " + keyDropbear.getAbsolutePath() + " not found")
          false
        }
      }) getOrElse false
    } catch {
      case e =>
        log.error(e.getMessage, e)
        false
    }
    @Loggable
    private def writeOpenSSHKey(context: Context, user: UserInfo): Boolean = try {
      (for {
        keyDropbear <- getDropbearKeyFile(context, user)
        keyOpenSSH <- getOpenSSHKeyFile(context, user)
        path <- AppControl.Inner.getInternalDirectory(DTimeout.long)
      } yield {
        IAmMumble("write OpenSSH key to " + keyOpenSSH.getAbsolutePath())
        if (keyDropbear.exists) {
          val dropbearconvert = new File(path, "dropbearconvert").getAbsolutePath()
          if (keyOpenSSH.exists())
            keyOpenSSH.delete()
          if (!keyOpenSSH.getParentFile.exists)
            keyOpenSSH.getParentFile.mkdirs
          val p = Runtime.getRuntime().exec(Array(dropbearconvert, "dropbear", "openssh",
            keyDropbear.getAbsolutePath(), keyOpenSSH.getAbsolutePath()))
          val err = new BufferedReader(new InputStreamReader(p.getErrorStream()))
          p.waitFor()
          val retcode = p.exitValue()
          val result = if (retcode != 0) {
            var error = err.readLine()
            while (error != null) {
              log.error(dropbearconvert + " error: " + error)
              IAmYell("dropbearconvert: " + error)
              error = err.readLine()
            }
            false
          } else
            true
          if (result)
            keyOpenSSH.setReadable(true, false)
          result
        } else {
          IAmYell("private key " + keyDropbear.getAbsolutePath() + " not found")
          false
        }
      }) getOrElse false
    } catch {
      case e =>
        log.error(e.getMessage, e)
        false
    }
  }
  object Dialog {
    @Loggable
    def createDialogUserEnable(context: Context, user: UserInfo, callback: (UserInfo) => Any): AlertDialog = {
      val title = Android.getString(context, "users_enable_title").getOrElse("Enable user \"%s\"").format(user.name)
      val message = Android.getString(context, "users_enable_message").getOrElse("Do you want to enable \"%s\" account?").format(user.name)
      val notification = Android.getString(context, "users_enabled_message").getOrElse("enabled user \"%s\"").format(user.name)
      createDialogUserChangeState(context, title, message, notification, true, user, callback)
    }
    @Loggable
    def createDialogUserDisable(context: Context, user: UserInfo, callback: (UserInfo) => Any): AlertDialog = {
      val title = Android.getString(context, "users_disable_title").getOrElse("Disable user \"%s\"").format(user.name)
      val message = Android.getString(context, "users_disable_message").getOrElse("Do you want to disable \"%s\" account?").format(user.name)
      val notification = Android.getString(context, "users_disabled_message").getOrElse("disabled user \"%s\"").format(user.name)
      createDialogUserChangeState(context, title, message, notification, false, user, callback)
    }
    def createDialogGenerateUserKey(context: Context, user: UserInfo): AlertDialog = {
      val defaultLengthIndex = 2
      val keyLength = Array[CharSequence]("4096 bits (only RSA)", "2048 bits (only RSA)", "1024 bits (RSA and DSA)")
      val title = Android.getString(context, "user_generate_key_title").getOrElse("Generate key for \"%s\"").format("android")
      new AlertDialog.Builder(context).
        setTitle(title).
        setSingleChoiceItems(keyLength, defaultLengthIndex, new DialogInterface.OnClickListener() {
          def onClick(dialog: DialogInterface, item: Int) =
            dialog.asInstanceOf[AlertDialog].getButton(DialogInterface.BUTTON_NEUTRAL).
              setEnabled(item == defaultLengthIndex)
        }).
        setNeutralButton("DSA", new DialogInterface.OnClickListener() {
          def onClick(dialog: DialogInterface, whichButton: Int) = {
            val lv = dialog.asInstanceOf[AlertDialog].getListView
            // leave UI thread
            Futures.future { Key.generateDSAKey(lv.getContext, user) }
          }
        }).
        setPositiveButton("RSA", new DialogInterface.OnClickListener() {
          def onClick(dialog: DialogInterface, whichButton: Int) {
            val lv = dialog.asInstanceOf[AlertDialog].getListView
            val length = lv.getCheckedItemPosition match {
              case 0 => 4096
              case 1 => 2048
              case 2 => 1024
            }
            // leave UI thread
            Futures.future { Key.generateRSAKey(lv.getContext, user, length) }
          }
        }).
        setNegativeButton(_root_.android.R.string.cancel, null).
        setIcon(_root_.android.R.drawable.ic_dialog_alert).
        create()
    }
    @Loggable
    def createDialogUserChangePassword(context: Context, user: UserInfo, callback: (UserInfo) => Any): AlertDialog = {
      val maxLengthFilter = new InputFilter.LengthFilter(5)
      val passwordLayout = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE).asInstanceOf[LayoutInflater].
        inflate(R.layout.alertdialog_text, null).asInstanceOf[LinearLayout]
      val togglePasswordButton = new ImageButton(context)
      togglePasswordButton.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
      togglePasswordButton.setImageResource(R.drawable.btn_eye)
      togglePasswordButton.setBackgroundResource(_root_.android.R.color.transparent)
      passwordLayout.addView(togglePasswordButton)
      val passwordField = passwordLayout.findViewById(_root_.android.R.id.edit).asInstanceOf[EditText]
      passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
      passwordField.setText(user.password)
      passwordField.setFilters(Array(SSHDUsers.userPasswordFilter))
      val dialog = new AlertDialog.Builder(context).
        setTitle(Android.getString(context, "dialog_password_title").getOrElse("'%s' user password").format(user.name)).
        setMessage(Html.fromHtml(Android.getString(context, "dialog_password_message").
          getOrElse("Please select a password. User password must be at least 1 characters long. Password cannot be more than 16 characters. Only standard unix password characters are allowed."))).
        setView(passwordLayout).
        setPositiveButton(_root_.android.R.string.ok, new DialogInterface.OnClickListener() {
          def onClick(dialog: DialogInterface, whichButton: Int) = try {
            val notification = Android.getString(context, "users_change_password").getOrElse("password changed for user %1$s").format(user.name)
            IAmWarn(notification.format(user.name))
            Toast.makeText(context, notification.format(user.name), Toast.LENGTH_SHORT).show()
            val newUser = user.copy(password = passwordField.getText.toString)
            save(context, newUser)
            adapter.foreach {
              adapter =>
                val position = adapter.getPosition(user)
                if (position >= 0) {
                  adapter.remove(user)
                  adapter.insert(newUser, position)
                }
            }
            activity.foreach {
              activity =>
                activity.updateFieldsState()
                if (activity.lastActiveUserInfo.get.exists(_ == user))
                  activity.lastActiveUserInfo.set(Some(newUser))
            }
            callback(newUser)
          } catch {
            case e =>
              log.error(e.getMessage, e)
          }
        }).
        setNegativeButton(_root_.android.R.string.cancel, null).
        setIcon(_root_.android.R.drawable.ic_dialog_info).
        create()
      dialog.show()
      val ok = dialog.findViewById(_root_.android.R.id.button1)
      ok.setEnabled(false)
      togglePasswordButton.setOnClickListener(new View.OnClickListener {
        val showPassword = new AtomicBoolean(false)
        def onClick(v: View) {
          if (showPassword.compareAndSet(true, false)) {
            togglePasswordButton.setSelected(false)
            passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
            passwordField.setTransformationMethod(PasswordTransformationMethod.getInstance())
          } else {
            showPassword.set(true)
            togglePasswordButton.setSelected(true)
            passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
          }
        }
      })
      passwordField.addTextChangedListener(new TextWatcher {
        override def afterTextChanged(s: Editable) = try {
          val newPassword = s.toString
          if (newPassword.nonEmpty) {
            ok.setEnabled(newPassword != user.password)
          } else
            ok.setEnabled(false)
        } catch {
          case e =>
            log.warn(e.getMessage, e)
        }
        override def beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override def onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
      })
      dialog
    }
    @Loggable
    private def createDialogUserChangeState(context: Context, title: String, message: String, notification: String, newState: Boolean, user: UserInfo, callback: (UserInfo) => Any): AlertDialog =
      new AlertDialog.Builder(context).
        setTitle(title).
        setMessage(message).
        setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
          def onClick(dialog: DialogInterface, whichButton: Int) = {
            IAmWarn(notification)
            Toast.makeText(context, notification, Toast.LENGTH_SHORT).show()
            val newUser = user.copy(enabled = newState)
            save(context, newUser)
            adapter.foreach {
              adapter =>
                val position = adapter.getPosition(user)
                if (position >= 0) {
                  adapter.remove(user)
                  adapter.insert(newUser, position)
                }
            }
            activity.foreach {
              activity =>
                activity.updateFieldsState()
                if (activity.lastActiveUserInfo.get.exists(_ == user))
                  activity.lastActiveUserInfo.set(Some(newUser))
            }
            callback(newUser)
          }
        }).
        setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
          def onClick(dialog: DialogInterface, whichButton: Int) =
            callback(user)
        }).
        setIcon(android.R.drawable.ic_dialog_alert).
        create()
  }
}
