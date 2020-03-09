/*
 * docker registry
 * No description provided (generated by Openapi Generator https://github.com/openapitools/openapi-generator)
 *
 * The version of the OpenAPI document: 1.0
 * 
 *
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */


package org.openapitools.client.model;

import java.util.Objects;
import java.util.Arrays;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.openapitools.client.model.Manifest;

/**
 * V2ManifestResult
 */
@javax.annotation.Generated(value = "org.openapitools.codegen.languages.JavaClientCodegen", date = "2020-03-09T13:45:19.880843+08:00[Asia/Shanghai]")
public class V2ManifestResult {
  public static final String SERIALIZED_NAME_SCHEMA_VERSION = "schemaVersion";
  @SerializedName(SERIALIZED_NAME_SCHEMA_VERSION)
  private Integer schemaVersion;

  public static final String SERIALIZED_NAME_MEDIA_TYPE = "mediaType";
  @SerializedName(SERIALIZED_NAME_MEDIA_TYPE)
  private String mediaType;

  public static final String SERIALIZED_NAME_CONFIG = "config";
  @SerializedName(SERIALIZED_NAME_CONFIG)
  private Manifest config;

  public static final String SERIALIZED_NAME_LAYERS = "layers";
  @SerializedName(SERIALIZED_NAME_LAYERS)
  private List<Manifest> layers = null;


  public V2ManifestResult schemaVersion(Integer schemaVersion) {
    
    this.schemaVersion = schemaVersion;
    return this;
  }

   /**
   * Get schemaVersion
   * @return schemaVersion
  **/
  @javax.annotation.Nullable
  @ApiModelProperty(value = "")

  public Integer getSchemaVersion() {
    return schemaVersion;
  }


  public void setSchemaVersion(Integer schemaVersion) {
    this.schemaVersion = schemaVersion;
  }


  public V2ManifestResult mediaType(String mediaType) {
    
    this.mediaType = mediaType;
    return this;
  }

   /**
   * Get mediaType
   * @return mediaType
  **/
  @javax.annotation.Nullable
  @ApiModelProperty(value = "")

  public String getMediaType() {
    return mediaType;
  }


  public void setMediaType(String mediaType) {
    this.mediaType = mediaType;
  }


  public V2ManifestResult config(Manifest config) {
    
    this.config = config;
    return this;
  }

   /**
   * Get config
   * @return config
  **/
  @javax.annotation.Nullable
  @ApiModelProperty(value = "")

  public Manifest getConfig() {
    return config;
  }


  public void setConfig(Manifest config) {
    this.config = config;
  }


  public V2ManifestResult layers(List<Manifest> layers) {
    
    this.layers = layers;
    return this;
  }

  public V2ManifestResult addLayersItem(Manifest layersItem) {
    if (this.layers == null) {
      this.layers = new ArrayList<Manifest>();
    }
    this.layers.add(layersItem);
    return this;
  }

   /**
   * Get layers
   * @return layers
  **/
  @javax.annotation.Nullable
  @ApiModelProperty(value = "")

  public List<Manifest> getLayers() {
    return layers;
  }


  public void setLayers(List<Manifest> layers) {
    this.layers = layers;
  }


  @Override
  public boolean equals(java.lang.Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    V2ManifestResult v2ManifestResult = (V2ManifestResult) o;
    return Objects.equals(this.schemaVersion, v2ManifestResult.schemaVersion) &&
        Objects.equals(this.mediaType, v2ManifestResult.mediaType) &&
        Objects.equals(this.config, v2ManifestResult.config) &&
        Objects.equals(this.layers, v2ManifestResult.layers);
  }

  @Override
  public int hashCode() {
    return Objects.hash(schemaVersion, mediaType, config, layers);
  }


  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class V2ManifestResult {\n");
    sb.append("    schemaVersion: ").append(toIndentedString(schemaVersion)).append("\n");
    sb.append("    mediaType: ").append(toIndentedString(mediaType)).append("\n");
    sb.append("    config: ").append(toIndentedString(config)).append("\n");
    sb.append("    layers: ").append(toIndentedString(layers)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(java.lang.Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }

}

