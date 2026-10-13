package com.otakulog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.otakulog.dto.RecommendationDTO.Settings;
import java.lang.reflect.ParameterizedType;
import java.time.LocalDateTime;
import java.util.*;

public final class RecommendationJson {
    private RecommendationJson() {}
    public static void strict(JsonNode node,Class<?> type,boolean defaultKey){
        if(node==null||!node.isObject())throw new IllegalArgumentException("推荐存档须为完整对象");
        var fields=new HashSet<String>();for(var field:type.getRecordComponents())fields.add(field.getName());
        node.fieldNames().forEachRemaining(name -> {if(!fields.contains(name))throw new IllegalArgumentException("推荐存档包含未知字段");});
        for(var field:type.getRecordComponents()){
            if(!node.has(field.getName()))throw new IllegalArgumentException("推荐存档缺少字段："+field.getName());
            var value=node.get(field.getName());Class<?> kind=field.getType();boolean valid;
            if(kind.isRecord()){strict(value,kind,false);continue;}
            if(kind==String.class)valid=value.isTextual()||(defaultKey&&type==Settings.class&&field.getName().equals("key")&&value.isNull());
            else if(kind==Integer.class)valid=(value.isNull()&&(field.getName().equals("minYear")||field.getName().equals("maxYear")))||(value.isIntegralNumber()&&value.canConvertToInt());
            else if(kind==Long.class)valid=value.isIntegralNumber()&&value.canConvertToLong();
            else if(kind==boolean.class)valid=value.isBoolean();
            else if(kind.isEnum()||kind==LocalDateTime.class)valid=value.isTextual();
            else if(kind==List.class){
                valid=value.isArray();if(valid){var element=((ParameterizedType)field.getGenericType()).getActualTypeArguments()[0];
                    for(var item:value)if(element==String.class?!item.isTextual():(!item.isIntegralNumber()||!item.canConvertToInt()))valid=false;}
            }else valid=false;
            if(!valid)throw new IllegalArgumentException("推荐字段类型无效："+field.getName());
        }
    }
}
