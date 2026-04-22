"""LangGraph workflow graph builder for supervisor agent"""
from langgraph.graph import StateGraph, END

from supervisor_agent.utils.constants.constants import NODE_ROUTER, NODE_WEATHER, NODE_TRAVEL, NODE_CHAT
from supervisor_agent.utils.models.models import AgentState
from supervisor_agent.utils.workflow.workflow_nodes import (
    router_node,
    weather_node,
    travel_node,
    chat_node,
    route_after_router,
    route_after_weather
)


def build_workflow() -> StateGraph:
    """
    Build and compile the LangGraph workflow graph.

    Workflow Structure:
        1. Router Node: Detects user intent (weather/travel/chat)
        2. Weather Node: Fetches weather information (required for travel)
        3. Travel Node: Generates travel plans (conditional on weather)
        4. Chat Node: Handles general conversation

    Returns:
        Compiled LangGraph workflow ready for execution
    """
    # Initialize state graph with AgentState schema
    workflow = StateGraph(AgentState)

    # Register workflow nodes
    workflow.add_node(NODE_ROUTER, router_node)
    workflow.add_node(NODE_WEATHER, weather_node)
    workflow.add_node(NODE_TRAVEL, travel_node)
    workflow.add_node(NODE_CHAT, chat_node)

    # Set router as the entry point
    workflow.set_entry_point(NODE_ROUTER)

    # Define routing logic from router node based on detected intent
    workflow.add_conditional_edges(
        NODE_ROUTER,
        route_after_router,
        {
            NODE_WEATHER: NODE_WEATHER,  # Weather query or travel planning (starts with weather)
            NODE_TRAVEL: NODE_WEATHER,   # Travel also requires weather check first
            NODE_CHAT: NODE_CHAT,        # General conversation
        }
    )

    # Define routing logic after weather node completion
    workflow.add_conditional_edges(
        NODE_WEATHER,
        route_after_weather,
        {
            NODE_TRAVEL: NODE_TRAVEL,  # Proceed to travel planning if intent is travel
            END: END                    # End workflow if only weather info was requested
        }
    )

    # Define terminal edges - these nodes complete the workflow
    workflow.add_edge(NODE_TRAVEL, END)  # Travel plan generated
    workflow.add_edge(NODE_CHAT, END)    # Chat response completed

    # Compile and return the workflow graph
    return workflow.compile()

