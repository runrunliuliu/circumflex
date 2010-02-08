/*
 * Copyright (C) 2009-2010 Boris Okunskiy (http://incarnate.ru)
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY AUTHOR AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL AUTHOR OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 */

package ru.circumflex.orm.i18n

import ru.circumflex.orm._
import ru.circumflex.core._
import java.sql.Connection
import java.lang.String
import org.slf4j.LoggerFactory
import java.util.Locale
import ORM._

/**
 * ORM Internationalization features configuration.
 */
object ORMI18N extends JDBCHelper {

  val log = LoggerFactory.getLogger("ru.circumflex.orm")

  val langSetting = Circumflex.cfg("orm.i18n.langSetting") match {
    case Some(s: String) => s
    case _ => "cx.lang"
  }

  def setLanguage(locale: Locale): Unit = setLanguage(locale.getLanguage)

  def setLanguage(lang: String): Unit =
    setLanguage(ORM.transactionManager.getTransaction.connection, lang)

  def setLanguage(conn: Connection, lang: String): Unit = {
    val q = i18nDialect.setLangQuery(lang.trim.toLowerCase);
    transactionManager.dml {
      conn => auto(conn.prepareStatement(q))(st => {
        sqlLog.debug(q)
        st.executeUpdate
        log.debug("Set transaction language to " + lang)
      })
    }
  }

  def i18nDialect: I18NDialect = ORM.dialect match {
    case d: I18NDialect => d
    case _ => throw new ORMException("'orm.dialect' must point to valid I18NDialect.")
  }

}