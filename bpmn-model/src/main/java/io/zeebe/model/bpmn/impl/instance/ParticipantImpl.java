/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
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

package io.zeebe.model.bpmn.impl.instance;

import static io.zeebe.model.bpmn.impl.BpmnModelConstants.BPMN20_NS;
import static io.zeebe.model.bpmn.impl.BpmnModelConstants.BPMN_ATTRIBUTE_NAME;
import static io.zeebe.model.bpmn.impl.BpmnModelConstants.BPMN_ATTRIBUTE_PROCESS_REF;
import static io.zeebe.model.bpmn.impl.BpmnModelConstants.BPMN_ELEMENT_PARTICIPANT;

import io.zeebe.model.bpmn.instance.BaseElement;
import io.zeebe.model.bpmn.instance.EndPoint;
import io.zeebe.model.bpmn.instance.Interface;
import io.zeebe.model.bpmn.instance.Participant;
import io.zeebe.model.bpmn.instance.ParticipantMultiplicity;
import io.zeebe.model.bpmn.instance.Process;
import java.util.Collection;
import org.camunda.bpm.model.xml.ModelBuilder;
import org.camunda.bpm.model.xml.impl.instance.ModelTypeInstanceContext;
import org.camunda.bpm.model.xml.type.ModelElementTypeBuilder;
import org.camunda.bpm.model.xml.type.ModelElementTypeBuilder.ModelTypeInstanceProvider;
import org.camunda.bpm.model.xml.type.attribute.Attribute;
import org.camunda.bpm.model.xml.type.child.ChildElement;
import org.camunda.bpm.model.xml.type.child.SequenceBuilder;
import org.camunda.bpm.model.xml.type.reference.AttributeReference;
import org.camunda.bpm.model.xml.type.reference.ElementReferenceCollection;

/**
 * The BPMN participant element
 *
 * @author Sebastian Menski
 */
public class ParticipantImpl extends BaseElementImpl implements Participant {

  protected static Attribute<String> nameAttribute;
  protected static AttributeReference<Process> processRefAttribute;
  protected static ElementReferenceCollection<Interface, InterfaceRef> interfaceRefCollection;
  protected static ElementReferenceCollection<EndPoint, EndPointRef> endPointRefCollection;
  protected static ChildElement<ParticipantMultiplicity> participantMultiplicityChild;

  public ParticipantImpl(ModelTypeInstanceContext instanceContext) {
    super(instanceContext);
  }

  public static void registerType(ModelBuilder modelBuilder) {
    final ModelElementTypeBuilder typeBuilder =
        modelBuilder
            .defineType(Participant.class, BPMN_ELEMENT_PARTICIPANT)
            .namespaceUri(BPMN20_NS)
            .extendsType(BaseElement.class)
            .instanceProvider(
                new ModelTypeInstanceProvider<Participant>() {
                  @Override
                  public Participant newInstance(ModelTypeInstanceContext instanceContext) {
                    return new ParticipantImpl(instanceContext);
                  }
                });

    nameAttribute = typeBuilder.stringAttribute(BPMN_ATTRIBUTE_NAME).build();

    processRefAttribute =
        typeBuilder
            .stringAttribute(BPMN_ATTRIBUTE_PROCESS_REF)
            .qNameAttributeReference(Process.class)
            .build();

    final SequenceBuilder sequenceBuilder = typeBuilder.sequence();

    interfaceRefCollection =
        sequenceBuilder
            .elementCollection(InterfaceRef.class)
            .qNameElementReferenceCollection(Interface.class)
            .build();

    endPointRefCollection =
        sequenceBuilder
            .elementCollection(EndPointRef.class)
            .qNameElementReferenceCollection(EndPoint.class)
            .build();

    participantMultiplicityChild = sequenceBuilder.element(ParticipantMultiplicity.class).build();

    typeBuilder.build();
  }

  @Override
  public String getName() {
    return nameAttribute.getValue(this);
  }

  @Override
  public void setName(String name) {
    nameAttribute.setValue(this, name);
  }

  @Override
  public Process getProcess() {
    return processRefAttribute.getReferenceTargetElement(this);
  }

  @Override
  public void setProcess(Process process) {
    processRefAttribute.setReferenceTargetElement(this, process);
  }

  @Override
  public Collection<Interface> getInterfaces() {
    return interfaceRefCollection.getReferenceTargetElements(this);
  }

  @Override
  public Collection<EndPoint> getEndPoints() {
    return endPointRefCollection.getReferenceTargetElements(this);
  }

  @Override
  public ParticipantMultiplicity getParticipantMultiplicity() {
    return participantMultiplicityChild.getChild(this);
  }

  @Override
  public void setParticipantMultiplicity(ParticipantMultiplicity participantMultiplicity) {
    participantMultiplicityChild.setChild(this, participantMultiplicity);
  }
}
