package com.platform.productservice.domain.entity;

import com.platform.productservice.domain.enums.AttributeType;

import java.util.ArrayList;
import java.util.List;

/**
 * Category attribute definition.
 * Defines an attribute that products in a category must or may satisfy.
 */
public class CategoryAttribute {

    private String id;
    private String attributeId;
    private String attributeName;
    private AttributeType attributeType;
    private boolean isRequired;
    private boolean isVariant;
    private List<String> values;

    public CategoryAttribute() {
        this.values = new ArrayList<>();
    }

    public CategoryAttribute(String id, String attributeId, String attributeName,
                            AttributeType attributeType, boolean isRequired,
                            boolean isVariant, List<String> values) {
        this.id = id;
        this.attributeId = attributeId;
        this.attributeName = attributeName;
        this.attributeType = attributeType;
        this.isRequired = isRequired;
        this.isVariant = isVariant;
        this.values = values != null ? new ArrayList<>(values) : new ArrayList<>();
    }

    public String getId() { return id; }
    public String getAttributeId() { return attributeId; }
    public String getAttributeName() { return attributeName; }
    public AttributeType getAttributeType() { return attributeType; }
    public boolean isRequired() { return isRequired; }
    public boolean isVariant() { return isVariant; }
    public List<String> getValues() { return values; }

    public void setId(String id) { this.id = id; }
    public void setAttributeId(String attributeId) { this.attributeId = attributeId; }
    public void setAttributeName(String attributeName) { this.attributeName = attributeName; }
    public void setAttributeType(AttributeType attributeType) { this.attributeType = attributeType; }
    public void setRequired(boolean required) { isRequired = required; }
    public void setVariant(boolean variant) { isVariant = variant; }
    public void setValues(List<String> values) { this.values = values != null ? new ArrayList<>(values) : new ArrayList<>(); }
}
