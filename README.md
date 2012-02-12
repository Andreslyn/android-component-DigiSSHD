DigiSSHD
========

This is a [DigiControl](http://github.com/ezh/android-DigiControl) component that contain SSH native binary for INETD and UI to tune service options. It is based on __latest__ stable version of [Dropbear](https://matt.ucc.asn.au/dropbear/dropbear.html) __v2011.54__ SSH Server and [OpenSSH](http://www.openssh.com/) __v5.9p1__ SFTP Server

Source code contains no Dropbear or OpenSSH code, because we avoid duplication of open-source software that is available for everyone. There are a patchset that apply to unpacked source before compilation in src/patchset

Patches apply to original software in ascend order with command ```patch -p1 -d ../../jni/dropbear/ < $i```

Feel free to translate or modify documentation, edit wiki or add comments.

Having question, suggestion or idea about the software? Contact us or [open issue](http://github.com/ezh/android-component-DigiSSHD/issues).

##### Contacts:

* email: alexey.ezh@gmail.com
* tel: 7-495-5185377
* skype: ezhariur

DOCUMENTATION
-------------

  [http://github.com/ezh/android-component-DigiSSHD/wiki](http://github.com/ezh/android-component-DigiSSHD/wiki)

AUTHORS
-------

* Alexey Aksenov

LICENSE
-------

The DigiSSHD Project is licensed to you under the terms of
the GNU General Public License (GPL) version 3 or later,
a copy of which has been included in the LICENSE file.
Please check the individual source files for details.

_PS FYI GitHub Wikis are the simplest way to contribute content. Any GitHub user can create and edit pages to use for documentation, examples, support or anything you wish._
