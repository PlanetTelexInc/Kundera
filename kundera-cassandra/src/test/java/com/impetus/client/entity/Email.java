/*******************************************************************************
 * * Copyright 2011 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.client.entity;

import java.util.ArrayList;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;


/**
 * Entity class for Email.
 *
 * @author amresh.singh
 */

@Entity
@Table(name = "emails", schema = "Blog")
public class Email
{

    /** The message id. */
    @Id
    private String messageId;

    /** The subject. */
    @Column(name = "subject_email")
    private String subject;

    /** The body. */
    @Column(name = "body_email")
    private String body;

    // @OneToOne(cascade={CascadeType.ALL}, fetch=FetchType.LAZY)
    /** The from. */
    @Embedded
    private Contact from;

    // @OneToOne(cascade={CascadeType.ALL}, fetch=FetchType.LAZY)
    /** The to. */
    @Embedded
    private Contact to;

    // @OneToMany (cascade={CascadeType.ALL}, fetch=FetchType.LAZY)
    /** The attachments. */
    @Embedded
    private List<Attachment> attachments;

    /**
     * Instantiates a new email.
     */
    public Email()
    {

    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    public String toString()
    {
        return "MessageId: " + messageId + "\tFrom:" + from + "\tTo: " + to + "\tSubject: " + subject + "\tBody: "
                + body + "\tAttachments: " + attachments;
    }

    /**
     * Adds the attachment.
     *
     * @param attchment the attchment
     */
    public void addAttachment(Attachment attchment)
    {
        if (this.attachments == null || this.attachments.isEmpty())
        {
            this.attachments = new ArrayList<Attachment>();
        }
        this.attachments.add(attchment);
    }

    /**
     * Gets the message id.
     *
     * @return the messageId
     */
    public String getMessageId()
    {
        return messageId;
    }

    /**
     * Sets the message id.
     *
     * @param messageId the messageId to set
     */
    public void setMessageId(String messageId)
    {
        this.messageId = messageId;
    }

    /**
     * Gets the from.
     *
     * @return the from
     */
    public Contact getFrom()
    {
        return from;
    }

    /**
     * Sets the from.
     *
     * @param from the from to set
     */
    public void setFrom(Contact from)
    {
        this.from = from;
    }

    /**
     * Gets the to.
     *
     * @return the to
     */
    public Contact getTo()
    {
        return to;
    }

    /**
     * Sets the to.
     *
     * @param to the to to set
     */
    public void setTo(Contact to)
    {
        this.to = to;
    }

    /**
     * Gets the subject.
     *
     * @return the subject
     */
    public String getSubject()
    {
        return subject;
    }

    /**
     * Sets the subject.
     *
     * @param subject the subject to set
     */
    public void setSubject(String subject)
    {
        this.subject = subject;
    }

    /**
     * Gets the body.
     *
     * @return the body
     */
    public String getBody()
    {
        return body;
    }

    /**
     * Sets the body.
     *
     * @param body the body to set
     */
    public void setBody(String body)
    {
        this.body = body;
    }

    /**
     * Gets the attachments.
     *
     * @return the attachments
     */
    public List<Attachment> getAttachments()
    {
        return attachments;
    }

    /**
     * Sets the attachments.
     *
     * @param attachments the attachments to set
     */
    public void setAttachments(List<Attachment> attachments)
    {
        this.attachments = attachments;
    }

}
