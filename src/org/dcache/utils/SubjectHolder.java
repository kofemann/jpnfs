/*
 * Copyright (c) 2009 - 2013 Deutsches Elektronen-Synchroton,
 * Member of the Helmholtz Association, (DESY), HAMBURG, GERMANY
 *
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Library General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this program (see the file COPYING.LIB for more
 * details); if not, write to the Free Software Foundation, Inc.,
 * 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.dcache.utils;

import com.google.common.base.Joiner;
import java.security.Principal;
import java.util.Set;
import javax.security.auth.Subject;

/**
 * A helper class to manipulate {@link Subject}.
 */
public class SubjectHolder {

    private final Subject _subject;

    public SubjectHolder(Subject subject) {
        _subject = subject;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Subject: < ");
        Set<Principal> principals = _subject.getPrincipals();
        Joiner joiner = Joiner.on(',');
        synchronized(principals) {
            joiner.appendTo(sb, principals);
        }
        sb.append(" >");
        return sb.toString();
    }
}
