/*
 * ====================
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2008-2009 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of the Common Development
 * and Distribution License("CDDL") (the "License").  You may not use this file
 * except in compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://opensource.org/licenses/cddl1.php
 * See the License for the specific language governing permissions and limitations
 * under the License.
 *
 * When distributing the Covered Code, include this CDDL Header Notice in each file
 * and include the License file at http://opensource.org/licenses/cddl1.php.
 * If applicable, add the following below this CDDL Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 * ====================
 */
package org.identityconnectors.solaris.operation;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.identityconnectors.common.CollectionUtil;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeUtil;
import org.identityconnectors.framework.common.objects.OperationalAttributes;
import org.identityconnectors.solaris.SolarisConnection;
import org.identityconnectors.solaris.attr.NativeAttribute;
import org.identityconnectors.solaris.operation.search.SolarisEntry;

/**
 * Updates any {@link NativeAttribute}, except
 * {@link OperationalAttributes#PASSWORD_NAME}.
 *
 * @author David Adam
 *
 */
class UpdateNativeUser extends CommandSwitches {
    private static final Set<String> USERMOD_ERRORS = CollectionUtil.newSet("ERROR",
            "command not found", "not allowed to execute");

    private final static Map<NativeAttribute, String> UPDATE_SWITCHES;
    static {
        UPDATE_SWITCHES = new HashMap<NativeAttribute, String>(CommandSwitches.COMMON_SWITCHES);
        UPDATE_SWITCHES.put(NativeAttribute.NAME, "-l"); // for new username
                                                         // attribute
    }

    public static void updateUser(SolarisEntry entry, GuardedString password, SolarisConnection conn) {
        conn.doSudoStart();
        try {
            conn.executeMutexAcquireScript();

            // UPDATE OF ALL ATTRIBUTES EXCEPT PASSWORD
            String newName = null;
            try {
                newName = updateUserImpl(entry, conn);
            } finally {
                conn.executeMutexReleaseScript();
            }

            // PASSWORD UPDATE
            if (password != null) {
                // the username could have changed in update, so we need to
                // change the password for the new username:
                final SolarisEntry entryWithNewName =
                        (newName != null) ? new SolarisEntry.Builder(newName).addAllAttributesFrom(
                                entry).build() : entry;

                PasswdCommand.configureUserPassword(entryWithNewName, password, conn);
            }

            PasswdCommand.configurePasswordProperties(entry, conn);
        } finally {
            conn.doSudoReset();
        }
    }

    /**
     * preform the user update.
     *
     * @param entry
     * @param conn
     * @return the new username, null otherwise (if the username hasn't been
     *         changed).
     */
    private static String updateUserImpl(SolarisEntry entry, SolarisConnection conn) {
        Attribute nameAttr = entry.searchForAttribute(NativeAttribute.NAME);
        // newName is null, if the name hasn't changed.
        String newName = (nameAttr != null) ? AttributeUtil.getStringValue(nameAttr) : null;

        /*
         * UPDATE OF USER ATTRIBUTES (except password) {@see PasswdCommand}
         */
        String commandSwitches =
                CommandSwitches.formatCommandSwitches(entry, conn, UPDATE_SWITCHES);

        // TODO evaluate: this is based on SRA#getUpdateNativeUserScript, line
        // 452. But it doesn't make sense to add attributes that are not already
        // present in the replaceattrs.
        // if (newName != null) {
        // String newUserNameParams = " -l \"" + newName + "\" -G \"\"";
        // commandSwitches += newUserNameParams;
        // }

        if (commandSwitches.length() == 0) {
            return newName; // no update switch found, nothing to process
        }

        if (newName != null) {
            // The secondary groups the target user belongs to must
            // be extracted, the rename operation will temporarily
            // remove them to keep /etc/group clean.
            String groupsScript = getSecondaryGroupsScript(entry, conn);
            conn.executeCommand(groupsScript);
        }

        String cmd = conn.buildCommand(true, "usermod", commandSwitches, entry.getName());
        conn.executeCommand(cmd, USERMOD_ERRORS);

        // If this is a rename operation, check to see if the user's
        // home directory needs to be renamed as well.
        if (newName != null) {
            // This script will restore the secondary groups to the
            // renamed user.
            final String updateGroupsCmd =
                    conn.buildCommand(true, "usermod", "-G \"$WSGROUPS\"", newName);
            conn.executeCommand(updateGroupsCmd, USERMOD_ERRORS);

            // Test to see if the user's home directory is to be renamed.
            // If a new home directory was specified as part of the rename
            // then skip this.
            if (!commandSwitches.contains("-d ")) {
                // Rename the home directory of the user to match the new
                // user name. This will only be done if the basename of
                // the home directory matches the old username. Also, if
                // the renamed home directory already exists, then the
                // rename of the home directory will not occur.
                final String renameDirScript = getRenameDirScript(entry, conn, newName);
                conn.executeCommand(renameDirScript, USERMOD_ERRORS);
            }
        }

        return newName;
    }

    private static String getRenameDirScript(SolarisEntry entry, SolarisConnection conn,
            String newName) {
        // @formatter:off
        String renameDir =
            "NEWNAME=" + newName + "; " +
            "OLDNAME=" + entry.getName() + "; " +
            "OLDDIR=`" + conn.buildCommand(true, "logins") + " -ox -l $NEWNAME | cut -d: -f6`; " +
            "OLDBASE=`basename $OLDDIR`; " +
            "if [ \"$OLDNAME\" = \"$OLDBASE\" ]; then\n" +
              "PARENTDIR=`dirname $OLDDIR`; " +
              "NEWDIR=`echo $PARENTDIR/$NEWNAME`; " +
              "if [ ! -s $NEWDIR ]; then " +
                conn.buildCommand(true, "chown") + " $NEWNAME $OLDDIR; " +
                conn.buildCommand(true, "mv") + " -f $OLDDIR $NEWDIR; " +
                "if [ $? -eq 0 ]; then\n" +
                  conn.buildCommand(true, "usermod") + " -d $NEWDIR $NEWNAME; " +
                "fi; " +
              "fi; " +
            "fi";
        // @formatter:off
        return renameDir;
    }

    private static String getSecondaryGroupsScript(SolarisEntry entry,
            SolarisConnection conn) {
        String getGroups =
            "n=1; " +
            "WSGROUPS=; " +
            "GROUPSWORK=`" + conn.buildCommand(true, "logins") + " -m -l " + entry.getName() + " | awk '{ print $1 }'`; " +
            "for i in $GROUPSWORK; " +
            "do "  +
              "if [ $n -eq 1 ]; then\n" +
                "n=2; " +
              "else " +
                "if [ $n -eq 2 ]; then " +
                  "WSGROUPS=$i; " +
                  "n=3; " +
                "else\n" +
                  "WSGROUPS=`echo \"$WSGROUPS,$i\"`; " +
                "fi; " +
              "fi; " +
            "done";

        return getGroups;
    }
}
