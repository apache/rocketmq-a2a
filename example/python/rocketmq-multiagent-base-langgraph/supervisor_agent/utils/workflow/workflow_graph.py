"""LangGraph workflow graph builder"""
from langgraph.graph import StateGraph, END
from supervisor_agent.utils.models.models import AgentState
from supervisor_agent.utils.workflow.workflow_nodes import router_node, weather_node, travel_node, chat_node, \
    route_after_router, route_after_weather


def build_workflow():
    """Build and compile the LangGraph workflow"""
    # Initialize state graph with AgentState schema
    workflow = StateGraph(AgentState)

    # Register workflow nodes
    workflow.add_node("router", router_node)
    workflow.add_node("weather_node", weather_node)
    workflow.add_node("travel_node", travel_node)
    workflow.add_node("chat_node", chat_node)

    # Set router as the entry point
    workflow.set_entry_point("router")

    # Define routing logic from router node
    workflow.add_conditional_edges(
        "router",
        route_after_router,
        {
            "weather_node": "weather_node",  # Route to weather for weather/travel queries
            "travel_node": "weather_node",   # Travel also starts with weather check
            "chat_node": "chat_node",        # Route to chat for general conversation
        }
    )

    # Define routing logic after weather node
    workflow.add_conditional_edges(
        "weather_node",
        route_after_weather,
        {
            "travel_node": "travel_node",  # Continue to travel if weather is good
            END: END                        # End workflow if only weather info needed
        }
    )

    # Define terminal edges
    workflow.add_edge("travel_node", END)  # Travel node completes the workflow
    workflow.add_edge("chat_node", END)    # Chat node completes the workflow

    # Compile and return the workflow graph
    return workflow.compile()
