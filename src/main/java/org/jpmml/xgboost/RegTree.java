/*
 * Copyright (c) 2016 Villu Ruusmann
 *
 * This file is part of JPMML-XGBoost
 *
 * JPMML-XGBoost is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-XGBoost is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-XGBoost.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.xgboost;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import org.dmg.pmml.DataType;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.MathContext;
import org.dmg.pmml.MiningFunction;
import org.dmg.pmml.Predicate;
import org.dmg.pmml.SimplePredicate;
import org.dmg.pmml.True;
import org.dmg.pmml.tree.BranchNode;
import org.dmg.pmml.tree.LeafNode;
import org.dmg.pmml.tree.TreeModel;
import org.jpmml.converter.BaseNFeature;
import org.jpmml.converter.BinaryFeature;
import org.jpmml.converter.CategoryManager;
import org.jpmml.converter.ContinuousFeature;
import org.jpmml.converter.Feature;
import org.jpmml.converter.ModelUtil;
import org.jpmml.converter.PredicateManager;
import org.jpmml.converter.Schema;
import org.jpmml.converter.ValueUtil;

public class RegTree implements Loadable {

	private int num_roots;

	private int num_nodes;

	private int num_deleted;

	private int max_depth;

	private int num_feature;

	private int size_leaf_vector;

	private Node[] nodes;

	private NodeStat[] stats;


	public RegTree(){
	}

	@Override
	public void load(XGBoostDataInput input) throws IOException {
		this.num_roots = input.readInt();
		this.num_nodes = input.readInt();
		this.num_deleted = input.readInt();
		this.max_depth = input.readInt();
		this.num_feature = input.readInt();
		this.size_leaf_vector = input.readInt();

		input.readReserved(31);

		this.nodes = input.readObjectArray(Node.class, this.num_nodes);
		this.stats = input.readObjectArray(NodeStat.class, this.num_nodes);
	}

	public boolean isEmpty(){
		Node node = this.nodes[0];

		if(!node.is_leaf()){
			return false;
		} else

		{
			Float value = node.leaf_value();

			return ValueUtil.isZero(value);
		}
	}

	public TreeModel encodeTreeModel(PredicateManager predicateManager, Schema schema){
		org.dmg.pmml.tree.Node root = encodeNode(0, True.INSTANCE, new CategoryManager(), predicateManager, schema);

		TreeModel treeModel = new TreeModel(MiningFunction.REGRESSION, ModelUtil.createMiningSchema(schema.getLabel()), root)
			.setSplitCharacteristic(TreeModel.SplitCharacteristic.BINARY_SPLIT)
			.setMissingValueStrategy(TreeModel.MissingValueStrategy.DEFAULT_CHILD)
			.setMathContext(MathContext.FLOAT);

		return treeModel;
	}

	private org.dmg.pmml.tree.Node encodeNode(int index, Predicate predicate, CategoryManager categoryManager, PredicateManager predicateManager, Schema schema){
		Integer id = Integer.valueOf(index);

		Node node = this.nodes[index];

		if(!node.is_leaf()){
			int splitIndex = node.split_index();

			Feature feature = schema.getFeature(splitIndex);

			CategoryManager leftCategoryManager = categoryManager;
			CategoryManager rightCategoryManager = categoryManager;

			Predicate leftPredicate;
			Predicate rightPredicate;

			boolean defaultLeft;

			if(feature instanceof BaseNFeature){
				BaseNFeature baseFeature = (BaseNFeature)feature;

				FieldName name = baseFeature.getName();

				int splitValue = (int)(Float.intBitsToFloat(node.split_cond()) + 1f);

				java.util.function.Predicate<Object> valueFilter = categoryManager.getValueFilter(name);

				List<Object> leftValues = baseFeature.getValues((Integer base) -> (base < splitValue)).stream()
					.filter(valueFilter)
					.collect(Collectors.toList());

				List<Object> rightValues = baseFeature.getValues((Integer base) -> (base >= splitValue)).stream()
					.filter(valueFilter)
					.collect(Collectors.toList());

				if(leftValues.size() == 0){
					throw new IllegalArgumentException("Left branch is not selectable");
				} // End if

				if(rightValues.size() == 0){
					throw new IllegalArgumentException("Right branch is not selectable");
				}

				leftCategoryManager = leftCategoryManager.fork(name, leftValues);
				rightCategoryManager = rightCategoryManager.fork(name, rightValues);

				leftPredicate = predicateManager.createPredicate(baseFeature, leftValues);
				rightPredicate = predicateManager.createPredicate(baseFeature, rightValues);

				defaultLeft = node.default_left();
			} else

			if(feature instanceof BinaryFeature){
				BinaryFeature binaryFeature = (BinaryFeature)feature;

				Object value = binaryFeature.getValue();

				leftPredicate = predicateManager.createSimplePredicate(binaryFeature, SimplePredicate.Operator.NOT_EQUAL, value);
				rightPredicate = predicateManager.createSimplePredicate(binaryFeature, SimplePredicate.Operator.EQUAL, value);

				defaultLeft = true;
			} else

			{
				ContinuousFeature continuousFeature = feature.toContinuousFeature();

				Number splitValue = Float.intBitsToFloat(node.split_cond());

				DataType dataType = continuousFeature.getDataType();
				switch(dataType){
					case INTEGER:
						splitValue = (int)(splitValue.floatValue() + 1f);
						break;
					case FLOAT:
						break;
					default:
						throw new IllegalArgumentException("Expected integer or float data type for continuous feature " + continuousFeature.getName() + ", got " + dataType.value() + " data type");
				}

				leftPredicate = predicateManager.createSimplePredicate(continuousFeature, SimplePredicate.Operator.LESS_THAN, splitValue);
				rightPredicate = predicateManager.createSimplePredicate(continuousFeature, SimplePredicate.Operator.GREATER_OR_EQUAL, splitValue);

				defaultLeft = node.default_left();
			}

			org.dmg.pmml.tree.Node leftChild = encodeNode(node.cleft(), leftPredicate, leftCategoryManager, predicateManager, schema);
			org.dmg.pmml.tree.Node rightChild = encodeNode(node.cright(), rightPredicate, rightCategoryManager, predicateManager, schema);

			org.dmg.pmml.tree.Node result = new BranchNode(null, predicate)
				.setId(id)
				.setDefaultChild(defaultLeft ? leftChild.getId() : rightChild.getId())
				.addNodes(leftChild, rightChild);

			return result;
		} else

		{
			Float value = node.leaf_value();

			org.dmg.pmml.tree.Node result = new LeafNode(value, predicate)
				.setId(id);

			return result;
		}
	}
}