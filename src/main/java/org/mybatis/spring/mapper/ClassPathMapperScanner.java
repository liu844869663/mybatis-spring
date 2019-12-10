/**
 *    Copyright 2010-2016 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.mybatis.spring.mapper;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Set;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.StringUtils;

/**
 * A {@link ClassPathBeanDefinitionScanner} that registers Mappers by
 * {@code basePackage}, {@code annotationClass}, or {@code markerInterface}. If
 * an {@code annotationClass} and/or {@code markerInterface} is specified, only
 * the specified types will be searched (searching for all interfaces will be
 * disabled).
 * <p>
 * This functionality was previously a private class of
 * {@link MapperScannerConfigurer}, but was broken out in version 1.2.0.
 *
 * @author Hunter Presnall
 * @author Eduardo Macarron
 * 
 * @see MapperFactoryBean
 * @since 1.2.0
 */
public class ClassPathMapperScanner extends ClassPathBeanDefinitionScanner {

	/**
	 * 是否添加到 {@link org.apache.ibatis.session.Configuration} 中
	 */
	private boolean addToConfig = true;

	private SqlSessionFactory sqlSessionFactory;

	private SqlSessionTemplate sqlSessionTemplate;

	/**
	 * {@link #sqlSessionTemplate} 的 bean 名字
	 */
	private String sqlSessionTemplateBeanName;

	/**
	 * {@link #sqlSessionFactory} 的 bean 名字
	 */
	private String sqlSessionFactoryBeanName;

	/**
	 * 指定注解
	 */
	private Class<? extends Annotation> annotationClass;

	/**
	 * 指定接口
	 */
	private Class<?> markerInterface;

	/**
	 * MapperFactoryBean 对象
	 */
	private MapperFactoryBean<?> mapperFactoryBean = new MapperFactoryBean<Object>();

	public ClassPathMapperScanner(BeanDefinitionRegistry registry) {
		super(registry, false);
	}

	public void setAddToConfig(boolean addToConfig) {
		this.addToConfig = addToConfig;
	}

	public void setAnnotationClass(Class<? extends Annotation> annotationClass) {
		this.annotationClass = annotationClass;
	}

	public void setMarkerInterface(Class<?> markerInterface) {
		this.markerInterface = markerInterface;
	}

	public void setSqlSessionFactory(SqlSessionFactory sqlSessionFactory) {
		this.sqlSessionFactory = sqlSessionFactory;
	}

	public void setSqlSessionTemplate(SqlSessionTemplate sqlSessionTemplate) {
		this.sqlSessionTemplate = sqlSessionTemplate;
	}

	public void setSqlSessionTemplateBeanName(String sqlSessionTemplateBeanName) {
		this.sqlSessionTemplateBeanName = sqlSessionTemplateBeanName;
	}

	public void setSqlSessionFactoryBeanName(String sqlSessionFactoryBeanName) {
		this.sqlSessionFactoryBeanName = sqlSessionFactoryBeanName;
	}

	public void setMapperFactoryBean(MapperFactoryBean<?> mapperFactoryBean) {
		this.mapperFactoryBean = mapperFactoryBean != null ? mapperFactoryBean : new MapperFactoryBean<Object>();
	}

	/**
	 * Configures parent scanner to search for the right interfaces. It can search
	 * for all interfaces or just for those that extends a markerInterface or/and
	 * those annotated with the annotationClass
	 */
	public void registerFilters() {
		boolean acceptAllInterfaces = true; // 是否接受所有接口

		// if specified, use the given annotation and / or marker interface
		// 如果指定了注解，则添加 INCLUDE 过滤器 AnnotationTypeFilter 对象
		if (this.annotationClass != null) {
			addIncludeFilter(new AnnotationTypeFilter(this.annotationClass));
			acceptAllInterfaces = false;
		}

		// override AssignableTypeFilter to ignore matches on the actual marker interface
		 // 如果指定了接口，则添加 INCLUDE 过滤器 AssignableTypeFilter 对象
		if (this.markerInterface != null) {
			addIncludeFilter(new AssignableTypeFilter(this.markerInterface) {
				@Override
				protected boolean matchClassName(String className) {
					return false;
				}
			});
			acceptAllInterfaces = false; // 标记不是接受所有接口
		}

		// 如果接受所有接口，则添加自定义 INCLUDE 过滤器 TypeFilter ，全部返回 true
		if (acceptAllInterfaces) {
			// default include filter that accepts all classes
			addIncludeFilter(new TypeFilter() {
				@Override
				public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
						throws IOException {
					return true;
				}
			});
		}

		// exclude package-info.java
		// 添加 INCLUDE 过滤器，排除 package-info.java
		addExcludeFilter(new TypeFilter() {
			@Override
			public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
					throws IOException {
				String className = metadataReader.getClassMetadata().getClassName();
				return className.endsWith("package-info");
			}
		});
	}

	/**
	 * Calls the parent search that will search and register all the candidates.
	 * Then the registered objects are post processed to set them as
	 * MapperFactoryBeans
	 */
	@Override
	public Set<BeanDefinitionHolder> doScan(String... basePackages) {
		// <1> 执行扫描，获得包下符合的类们，并分装成 BeanDefinitionHolder 对象的集合
		Set<BeanDefinitionHolder> beanDefinitions = super.doScan(basePackages);

		if (beanDefinitions.isEmpty()) {
			logger.warn("No MyBatis mapper was found in '" + Arrays.toString(basePackages)
					+ "' package. Please check your configuration.");
		} else {
			// 处理 BeanDefinitionHolder 对象的集合
			processBeanDefinitions(beanDefinitions);
		}

		return beanDefinitions;
	}

	private void processBeanDefinitions(Set<BeanDefinitionHolder> beanDefinitions) {
		GenericBeanDefinition definition;
		// <1> 遍历 BeanDefinitionHolder 数组，逐一设置属性
		for (BeanDefinitionHolder holder : beanDefinitions) {
			definition = (GenericBeanDefinition) holder.getBeanDefinition();

			if (logger.isDebugEnabled()) {
				logger.debug("Creating MapperFactoryBean with name '" + holder.getBeanName() + "' and '"
						+ definition.getBeanClassName() + "' mapperInterface");
			}

			// the mapper interface is the original class of the bean but, the actual class of the bean is MapperFactoryBean
			// <2> 此处 definition 的 beanClass 为 Mapper 接口，需要修改成 MapperFactoryBean 类，从而创建 Mapper 代理对象
			definition.getConstructorArgumentValues().addGenericArgumentValue(definition.getBeanClassName()); // issue #59
			definition.setBeanClass(this.mapperFactoryBean.getClass());

			// <3> 设置 `MapperFactoryBean.addToConfig` 属性
			definition.getPropertyValues().add("addToConfig", this.addToConfig);

			boolean explicitFactoryUsed = false; // <4.1>是否已经显式设置了 sqlSessionFactory 或 sqlSessionFactory 属性
			// <4.2> 如果 sqlSessionFactoryBeanName 或 sqlSessionFactory 非空，设置到 `MapperFactoryBean.sqlSessionFactory` 属性
			if (StringUtils.hasText(this.sqlSessionFactoryBeanName)) {
				definition.getPropertyValues().add("sqlSessionFactory",
						new RuntimeBeanReference(this.sqlSessionFactoryBeanName));
				explicitFactoryUsed = true;
			} else if (this.sqlSessionFactory != null) {
				definition.getPropertyValues().add("sqlSessionFactory", this.sqlSessionFactory);
				explicitFactoryUsed = true;
			}

			// <4.3> 如果 sqlSessionTemplateBeanName 或 sqlSessionTemplate 非空，设置到 `MapperFactoryBean.sqlSessionTemplate` 属性
			if (StringUtils.hasText(this.sqlSessionTemplateBeanName)) {
				if (explicitFactoryUsed) {
					logger.warn(
							"Cannot use both: sqlSessionTemplate and sqlSessionFactory together. sqlSessionFactory is ignored.");
				}
				definition.getPropertyValues().add("sqlSessionTemplate",
						new RuntimeBeanReference(this.sqlSessionTemplateBeanName));
				explicitFactoryUsed = true;
			} else if (this.sqlSessionTemplate != null) {
				if (explicitFactoryUsed) {
					logger.warn(
							"Cannot use both: sqlSessionTemplate and sqlSessionFactory together. sqlSessionFactory is ignored.");
				}
				definition.getPropertyValues().add("sqlSessionTemplate", this.sqlSessionTemplate);
				explicitFactoryUsed = true;
			}

			// <4.4> 如果未显式设置，则设置根据类型自动注入
			if (!explicitFactoryUsed) {
				if (logger.isDebugEnabled()) {
					logger.debug("Enabling autowire by type for MapperFactoryBean with name '" + holder.getBeanName()
							+ "'.");
				}
				definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
		return beanDefinition.getMetadata().isInterface() && beanDefinition.getMetadata().isIndependent();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected boolean checkCandidate(String beanName, BeanDefinition beanDefinition) {
		if (super.checkCandidate(beanName, beanDefinition)) {
			return true;
		} else {
			logger.warn(
					"Skipping MapperFactoryBean with name '" + beanName + "' and '" + beanDefinition.getBeanClassName()
							+ "' mapperInterface" + ". Bean already defined with the same name!");
			return false;
		}
	}

}
