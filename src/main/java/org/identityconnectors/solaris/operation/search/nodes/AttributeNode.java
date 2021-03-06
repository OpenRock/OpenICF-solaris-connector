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
package org.identityconnectors.solaris.operation.search.nodes;

import java.util.Set;

import org.identityconnectors.solaris.attr.NativeAttribute;
import org.identityconnectors.solaris.operation.search.SolarisEntry;

/**
 * encapsulates matching of a single attribute by a regular expression.
 *
 * @author David Adam
 */
public abstract class AttributeNode implements Node {

    private final NativeAttribute attrName;

    /**
     * inverse matching, for instance match attribute whose value is not 'foo'.
     */
    private final boolean isNot;

    /**
     * @param nativeAttribute
     *            the attribute name that is filtered
     * @param isNot
     *            in case it is true this means inverse filter evaluation. (For
     *            instance equals filter becomes 'not equals').
     */
    public AttributeNode(final NativeAttribute nativeAttribute, final boolean isNot) {
        this.attrName = nativeAttribute;
        this.isNot = isNot;
    }

    public abstract boolean evaluate(SolarisEntry entry);

    /** @return true if the evaluation should be done in negative logic. */
    protected boolean isNot() {
        return isNot;
    }

    public final NativeAttribute getAttributeName() {
        return attrName;
    }

    /**
     * {@see org.identityconnectors.solaris.operation.search.nodes.Node#
     * collectAttributeNames(java.util.Set)}.
     */
    public void collectAttributeNames(Set<NativeAttribute> attrs) {
        attrs.add(getAttributeName());
    }

    @Override
    public String toString() {
        return String.format("Filter %s : name='%s'", getClass().getName(), attrName.getName());
    }
}
